package edu.must.campuslostfound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Independent HTTP workflow regression. Practice matching and reserved AT-05/06 data are not used. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:t19-workflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=15000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class T19WorkflowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AlertService alerts;

    record Client(String name, String role) {}
    Client finder, claimant, competitor, outsider, moderator, otherModerator;

    @BeforeEach
    void clean() {
        for (String table : List.of("moderation_audit", "claim_requests", "claim_audit", "claim_decisions", "return_confirmations", "case_messages", "review_assignments", "reports", "claims",
                "alert_event_deliveries", "match_alerts", "ignored_pairs", "match_candidates", "active_reservations", "claim_guards", "notice_photos",
                "notice_revisions", "notice_events", "notice_audit", "idempotency_records", "notices")) jdbc.update("DELETE FROM " + table);
        finder = client("finder", "STUDENT"); claimant = client("claimant", "STUDENT");
        competitor = client("competitor", "STUDENT"); outsider = client("outsider", "STUDENT");
        moderator = client("reviewer", "MODERATOR"); otherModerator = client("otherreviewer", "MODERATOR");
    }

    @Test
    void claimSubmissionEnforcesIdentityUnicodeLinkAndRetryBoundaries() throws Exception {
        String found = notice(finder, "FOUND");
        String lost = notice(claimant, "LOST");
        String foreignLost = notice(outsider, "LOST");
        mutation(finder, "/api/notices/" + found + "/claims", submitBody(null, "Private identifying marks on the blue bottle"), key()).andExpect(status().is4xxClientError());
        mutation(claimant, "/api/notices/" + found + "/claims", submitBody(foreignLost, "Private identifying marks on the blue bottle"), key()).andExpect(status().is4xxClientError());
        mutation(claimant, "/api/notices/" + found + "/claims", submitBody(null, "😀".repeat(9)), key()).andExpect(status().isUnprocessableEntity());
        mutation(claimant, "/api/notices/" + found + "/claims", submitBody(null, "😀".repeat(1001)), key()).andExpect(status().isUnprocessableEntity());
        mutation(claimant, "/api/notices/" + found + "/claims", submitBody(null, "A missing retry key must not write a claim"), null).andExpect(status().isUnprocessableEntity());
        String requestKey = key();
        Map<String,Object> body = submitBody(lost, "😀".repeat(10));
        JsonNode first = read(mutation(claimant, "/api/notices/" + found + "/claims", body, requestKey).andExpect(status().is2xxSuccessful()).andReturn());
        JsonNode replay = read(mutation(claimant, "/api/notices/" + found + "/claims", body, requestKey).andExpect(status().is2xxSuccessful()).andReturn());
        assertThat(replay.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(count("SELECT COUNT(*) FROM claims WHERE found_notice_id=?", found)).isEqualTo(1);
        mutation(claimant, "/api/notices/" + found + "/claims", submitBody(lost, "Different claim evidence and same request key"), requestKey).andExpect(status().isConflict());
        mutation(claimant, "/api/notices/" + found + "/claims", body, key()).andExpect(status().isConflict());
        String id = first.path("id").asText();
        fetch(finder, "/api/claims/" + id).andExpect(status().isOk());
        fetch(competitor, "/api/claims/" + id).andExpect(status().isForbidden());
        fetch(moderator, "/api/claims/" + id).andExpect(status().isForbidden());
        mvc.perform(get("/api/claims/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notices/" + found + "/claims").with(user(competitor.name())).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key()).content(mapper.writeValueAsBytes(submitBody(null, "A private claim that lacks CSRF")))).andExpect(status().isForbidden());
        assertThat(fetchJson(competitor, "/api/claims").toString()).doesNotContain("😀", id);
    }

    @Test
    void withdrawalRecomputesSharedNoticeLocksAndPreservesOtherPendingClaims() throws Exception {
        String found = notice(finder, "FOUND");
        String lost = notice(claimant, "LOST");
        String first = submit(claimant, found, lost);
        String second = submit(competitor, found, null);
        assertThat(state(found)).isEqualTo("CLAIM_UNDER_REVIEW");
        assertThat(fetchJson(finder, "/api/notices/" + found).path("canClose").asBoolean()).isFalse();
        assertThat(fetchJson(claimant, "/api/notices/" + lost).path("canClose").asBoolean()).isFalse();
        action(claimant, first, "withdraw", Map.of()).andExpect(status().is2xxSuccessful());
        assertThat(state(found)).isEqualTo("CLAIM_UNDER_REVIEW");
        assertThat(fetchJson(claimant, "/api/notices/" + lost).path("canClose").asBoolean()).isTrue();
        action(competitor, second, "withdraw", Map.of()).andExpect(status().is2xxSuccessful());
        assertThat(state(found)).isEqualTo("OPEN");
        assertThat(fetchJson(finder, "/api/notices/" + found).path("canClose").asBoolean()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM active_reservations")).isZero();
        assertThat(fetchJson(claimant, "/api/claims/" + first).path("status").asText()).isEqualTo("WITHDRAWN");
        action(claimant, first, "messages", Map.of("content", "Cannot write to a terminal claim")).andExpect(status().is4xxClientError());
    }

    @Test
    void twentyFreshTwoClaimRacesChooseOneWinnerAndReserveOnlyItsNotices() throws Exception {
        // Each iteration creates a new found notice AND two distinct claims. It is not twenty retries of one claim.
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int trial = 0; trial < 20; trial++) {
                String found = notice(finder, "FOUND");
                String lost1 = notice(claimant, "LOST");
                String lost2 = notice(competitor, "LOST");
                String claim1 = submit(claimant, found, lost1);
                String claim2 = submit(competitor, found, lost2);
                Map<String,Object> body1 = versioned(finder, claim1, Map.of("reason", "Verified private identifying evidence"));
                Map<String,Object> body2 = versioned(finder, claim2, Map.of("reason", "Verified private identifying evidence"));
                CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
                var race1 = executor.submit(() -> { ready.countDown(); assertThat(go.await(10,TimeUnit.SECONDS)).isTrue(); return mutation(finder,"/api/claims/"+claim1+"/approve",body1,key()).andReturn(); });
                var race2 = executor.submit(() -> { ready.countDown(); assertThat(go.await(10,TimeUnit.SECONDS)).isTrue(); return mutation(finder,"/api/claims/"+claim2+"/approve",body2,key()).andReturn(); });
                assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); go.countDown();
                int status1 = race1.get(30,TimeUnit.SECONDS).getResponse().getStatus();
                int status2 = race2.get(30,TimeUnit.SECONDS).getResponse().getStatus();
                assertThat(List.of(status1,status2)).as("fresh two-claim trial %s",trial+1).containsExactlyInAnyOrder(200,409);
                assertThat(count("SELECT COUNT(*) FROM claims WHERE found_notice_id=? AND status='APPROVED'",found)).isEqualTo(1);
                assertThat(count("SELECT COUNT(*) FROM claims WHERE found_notice_id=? AND status='HELD'",found)).isEqualTo(1);
                String winner = status1==200 ? claim1 : claim2;
                String winnerLost = status1==200 ? lost1 : lost2;
                String loserLost = status1==200 ? lost2 : lost1;
                assertThat(jdbc.queryForList("SELECT notice_id FROM active_reservations WHERE claim_id=?",String.class,winner)).containsExactlyInAnyOrder(found,winnerLost);
                assertThat(count("SELECT COUNT(*) FROM active_reservations WHERE notice_id=?",loserLost)).isZero();
                assertThat(state(found)).isEqualTo("AWAITING_COLLECTION");
                mutation(outsider,"/api/notices/"+found+"/claims",submitBody(null,"New claim must not enter after approval"),key()).andExpect(status().isConflict());
            }
        }
    }

    @Test
    void linkedLostNoticeCannotBeReservedByTwoDifferentFoundNotices() throws Exception {
        String found1 = notice(finder,"FOUND"), found2 = notice(competitor,"FOUND"), lost = notice(claimant,"LOST");
        String first = submit(claimant,found1,lost), second = submit(claimant,found2,lost);
        action(finder,first,"approve",Map.of("reason","Verified privately")).andExpect(status().is2xxSuccessful());
        action(competitor,second,"approve",Map.of("reason","Another found notice cannot reserve that lost item")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT claim_id FROM active_reservations WHERE notice_id=?",String.class,lost)).isEqualTo(first);
        assertThat(fetchJson(claimant,"/api/claims/"+second).path("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void messagesUseVersionsIdempotencyAndTerminalReadOnlyAccess() throws Exception {
        String found=notice(finder,"FOUND"), id=submit(claimant,found,null);
        Map<String,Object> message=versioned(claimant,id,Map.of("content","😀".repeat(1000)));
        String requestKey=key();
        mutation(claimant,"/api/claims/"+id+"/messages",message,requestKey).andExpect(status().is2xxSuccessful());
        mutation(claimant,"/api/claims/"+id+"/messages",message,requestKey).andExpect(status().is2xxSuccessful());
        assertThat(fetchJson(claimant,"/api/claims/"+id).path("messages")).hasSize(1);
        mutation(claimant,"/api/claims/"+id+"/messages",message,key()).andExpect(status().isConflict());
        action(claimant,id,"messages",Map.of("content","😀".repeat(1001))).andExpect(status().isUnprocessableEntity());
        action(finder,id,"reject",Map.of("reason","Evidence does not match the item")).andExpect(status().is2xxSuccessful());
        action(claimant,id,"messages",Map.of("content","Do not mutate a rejected case")).andExpect(status().is4xxClientError());
        assertThat(fetchJson(claimant,"/api/claims/"+id).path("messages")).hasSize(1);
        assertThat(state(found)).isEqualTo("OPEN");
    }

    @Test
    void bothParticipantsConfirmOnceAndTerminalStatesStopRediscovery() throws Exception {
        String found=notice(finder,"FOUND"), lost=notice(claimant,"LOST");
        String id=submit(claimant,found,lost), held=submit(competitor,found,null);
        action(finder,id,"approve",Map.of("reason","Verified the private mark")).andExpect(status().is2xxSuccessful());
        long beforeEvents=count("SELECT COUNT(*) FROM notice_events");
        Map<String,Object> finderBody=confirmationBody(finder,id); String finderKey=key();
        mutation(finder,"/api/claims/"+id+"/confirm-return",finderBody,finderKey).andExpect(status().is2xxSuccessful());
        assertThat(state(found)).isEqualTo("AWAITING_COLLECTION");
        assertThat(state(lost)).isEqualTo("OPEN");
        Map<String,Object> claimantBody=confirmationBody(claimant,id); String claimantKey=key();
        mutation(claimant,"/api/claims/"+id+"/confirm-return",claimantBody,claimantKey).andExpect(status().is2xxSuccessful());
        assertThat(state(found)).isEqualTo("RETURNED"); assertThat(state(lost)).isEqualTo("CLOSED");
        assertThat(fetchJson(claimant,"/api/claims/"+id).path("status").asText()).isEqualTo("ITEM_RETURNED");
        assertThat(fetchJson(competitor,"/api/claims/"+held).path("status").asText()).isEqualTo("ITEM_RETURNED");
        assertThat(count("SELECT COUNT(*) FROM active_reservations")).isZero();
        assertThat(count("SELECT COUNT(*) FROM notice_events")).isEqualTo(beforeEvents+2);
        mutation(finder,"/api/claims/"+id+"/confirm-return",finderBody,finderKey).andExpect(status().is2xxSuccessful());
        mutation(claimant,"/api/claims/"+id+"/confirm-return",claimantBody,claimantKey).andExpect(status().is2xxSuccessful());
        assertThat(count("SELECT COUNT(*) FROM notice_events")).isEqualTo(beforeEvents+2);
        assertThat(fetchJson(outsider,"/api/notices?scope=available").path("items").toString()).doesNotContain(found,lost);
        assertThat(fetchJson(claimant,"/api/notices/"+lost+"/suggestions").path("items")).isEmpty();
        int alertCount=(int)count("SELECT COUNT(*) FROM match_alerts");
        for(String event:jdbc.queryForList("SELECT id FROM notice_events WHERE notice_id=?",String.class,found)) alerts.replay(event);
        assertThat(count("SELECT COUNT(*) FROM match_alerts")).isEqualTo(alertCount);
        action(claimant,id,"messages",Map.of("content","Terminal case is read only")).andExpect(status().is4xxClientError());
    }

    @Test
    void assignedReviewerAloneSeesPrivateDisputeAndCannotConfirmForStudents() throws Exception {
        String found=notice(finder,"FOUND"), id=submit(claimant,found,null);
        action(finder,id,"approve",Map.of("reason","Verified the private engraving")).andExpect(status().is2xxSuccessful());
        action(finder,id,"confirm-return",Map.of()).andExpect(status().is2xxSuccessful());
        String report=reportClaim(claimant,id,"Private dispute detail about distinctive engraving");
        assertThat(fetchJson(claimant,"/api/claims/"+id).path("disputed").asBoolean()).isTrue();
        action(claimant,id,"confirm-return",Map.of()).andExpect(status().isConflict());
        assertThat(state(found)).isEqualTo("AWAITING_COLLECTION");
        JsonNode queue=fetchJson(moderator,"/api/moderation/reports");
        assertThat(queue.toString()).doesNotContain("Private dispute detail",claimant.name());
        fetch(moderator,"/api/claims/"+id).andExpect(status().isForbidden());
        mutation(moderator,"/api/moderation/reports/"+report+"/assign",Map.of("reason","Assigned to review this dispute"),key()).andExpect(status().is2xxSuccessful());
        assertThat(fetchJson(moderator,"/api/claims/"+id).path("evidence").asText()).contains("engraving");
        fetch(otherModerator,"/api/claims/"+id).andExpect(status().isForbidden());
        action(moderator,id,"messages",Map.of("content","Please add further explanation privately")).andExpect(status().is2xxSuccessful());
        action(claimant,id,"messages",Map.of("content","Additional explanation without editing earlier evidence")).andExpect(status().is2xxSuccessful());
        action(moderator,id,"confirm-return",Map.of()).andExpect(status().isForbidden());
        JsonNode privateReport=fetchJson(moderator,"/api/moderation/reports/"+report);
        mutation(otherModerator,"/api/moderation/reports/"+report+"/resolve",Map.of("resolution","RESUME_COLLECTION","note","Unassigned moderator cannot decide","version",privateReport.path("version").asLong()),key()).andExpect(status().is4xxClientError());
        resolve(moderator,report,"RESUME_COLLECTION","Both parties agree to resume collection after review");
        JsonNode resumed=fetchJson(claimant,"/api/claims/"+id);
        assertThat(resumed.path("disputed").asBoolean()).isFalse();
        assertThat(resumed.path("finderConfirmed").asBoolean()).isFalse();
        assertThat(resumed.path("claimantConfirmed").asBoolean()).isFalse();
        action(finder,id,"confirm-return",Map.of()).andExpect(status().is2xxSuccessful());
        action(claimant,id,"confirm-return",Map.of()).andExpect(status().is2xxSuccessful());
        assertThat(state(found)).isEqualTo("RETURNED");
        String postReturn=reportClaim(claimant,id,"Later report must preserve the actual return history");
        assertThat(state(found)).isEqualTo("RETURNED");
        assertThat(fetchJson(claimant,"/api/claims/"+id).path("status").asText()).isEqualTo("ITEM_RETURNED");
        assertThat(count("SELECT COUNT(*) FROM reports WHERE id=?",postReturn)).isEqualTo(1);
    }

    @Test
    void approvedCancellationRequiresAssignmentReleasesReservationsAndClearsConfirmation() throws Exception {
        String found=notice(finder,"FOUND"), lost=notice(claimant,"LOST");
        String id=submit(claimant,found,lost), other=submit(competitor,found,null);
        action(finder,id,"approve",Map.of("reason","Initial evidence reviewed")).andExpect(status().is2xxSuccessful());
        action(finder,id,"confirm-return",Map.of()).andExpect(status().is2xxSuccessful());
        action(claimant,id,"withdraw",Map.of()).andExpect(status().isConflict());
        String report=reportClaim(claimant,id,"Request review before collection is completed");
        String remainingReport=reportClaim(finder,id,"A separate explanation also needs review");
        mutation(moderator,"/api/moderation/reports/"+report+"/assign",Map.of("reason","Assigned cancellation review"),key()).andExpect(status().is2xxSuccessful());
        resolve(moderator,report,"CANCEL_APPROVAL","Evidence review requires cancelling the current approval");
        JsonNode cancelled=fetchJson(claimant,"/api/claims/"+id);
        assertThat(cancelled.path("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(cancelled.path("finderConfirmed").asBoolean()).isFalse();
        assertThat(cancelled.path("claimantConfirmed").asBoolean()).isFalse();
        assertThat(cancelled.path("disputed").asBoolean()).isTrue();
        assertThat(fetchJson(moderator,"/api/moderation/reports/"+remainingReport).path("status").asText()).isEqualTo("OPEN");
        resolve(moderator,remainingReport,"DISMISS","Separate explanation reviewed; cancellation remains unchanged");
        assertThat(fetchJson(claimant,"/api/claims/"+id).path("disputed").asBoolean()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM active_reservations WHERE claim_id=?",id)).isZero();
        assertThat(state(found)).isEqualTo("CLAIM_UNDER_REVIEW");
        assertThat(state(lost)).isEqualTo("OPEN");
        action(finder,other,"approve",Map.of("reason","Different claimant supplied matching evidence")).andExpect(status().is2xxSuccessful());
        assertThat(state(found)).isEqualTo("AWAITING_COLLECTION");
        assertThat(count("SELECT COUNT(*) FROM claim_decisions WHERE claim_id=?",id)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void hidingNoticeBlocksPublicAccessAndWorkflowsWithoutErasingHistory() throws Exception {
        String found=notice(finder,"FOUND"), lost=notice(claimant,"LOST"), id=submit(claimant,found,lost);
        String requestKey=key();
        mutation(outsider,"/api/notices/"+found+"/report",Map.of("reason","Public notice contains details requiring review"),requestKey).andExpect(status().is2xxSuccessful());
        mutation(outsider,"/api/notices/"+found+"/report",Map.of("reason","Public notice contains details requiring review"),requestKey).andExpect(status().is2xxSuccessful());
        assertThat(count("SELECT COUNT(*) FROM reports WHERE notice_id=?",found)).isEqualTo(1);
        String report=jdbc.queryForObject("SELECT id FROM reports WHERE notice_id=?",String.class,found);
        mutation(moderator,"/api/moderation/reports/"+report+"/assign",Map.of("reason","Assigned public notice review"),key()).andExpect(status().is2xxSuccessful());
        resolve(moderator,report,"HIDE_NOTICE","Hide public notice pending content review");
        fetch(outsider,"/api/notices/"+found).andExpect(status().isNotFound());
        assertThat(fetchJson(outsider,"/api/notices?scope=available").path("items").toString()).doesNotContain(found);
        assertThat(fetchJson(claimant,"/api/notices/"+lost+"/suggestions").path("items").toString()).doesNotContain(found);
        assertThat(fetchJson(claimant,"/api/claims/"+id).path("hidden").asBoolean()).isTrue();
        action(finder,id,"approve",Map.of("reason","A hidden notice must not proceed")).andExpect(status().isConflict());
        action(claimant,id,"messages",Map.of("content","Hidden case must not accept ordinary messages")).andExpect(status().is4xxClientError());
        assertThat(count("SELECT COUNT(*) FROM claims WHERE id=?",id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM moderation_audit WHERE report_id=?",report)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void aPendingDisputeCannotBeSilentlyClearedByApproval() throws Exception {
        String found=notice(finder,"FOUND"), id=submit(claimant,found,null);
        reportClaim(claimant,id,"Clarify ownership evidence before approving this claim");
        action(finder,id,"approve",Map.of("reason","Approval must not bypass an open dispute")).andExpect(status().isConflict());
        JsonNode current=fetchJson(claimant,"/api/claims/"+id);
        assertThat(current.path("status").asText()).isEqualTo("PENDING");
        assertThat(current.path("disputed").asBoolean()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM active_reservations")).isZero();
    }

    @Test
    void releasingReviewAssignmentRevokesPrivateAccessImmediately() throws Exception {
        String found=notice(finder,"FOUND"), id=submit(claimant,found,null);
        String report=reportClaim(claimant,id,"Private explanation for the assigned reviewer only");
        mutation(moderator,"/api/moderation/reports/"+report+"/assign",Map.of("reason","Take this case for review"),key()).andExpect(status().is2xxSuccessful());
        fetch(moderator,"/api/claims/"+id).andExpect(status().isOk());
        String releaseKey=key();
        mutation(moderator,"/api/moderation/reports/"+report+"/release",Map.of("reason","Return the case to the review queue"),releaseKey).andExpect(status().is2xxSuccessful());
        mutation(moderator,"/api/moderation/reports/"+report+"/release",Map.of("reason","Return the case to the review queue"),releaseKey).andExpect(status().is2xxSuccessful());
        fetch(moderator,"/api/claims/"+id).andExpect(status().isForbidden());
        assertThat(fetchJson(moderator,"/api/moderation/reports/"+report).toString()).doesNotContain("Private explanation",claimant.name());
        mutation(otherModerator,"/api/moderation/reports/"+report+"/assign",Map.of("reason","New reviewer takes this case"),key()).andExpect(status().is2xxSuccessful());
        assertThat(fetchJson(otherModerator,"/api/claims/"+id).path("reviewerId").asText()).isEqualTo(otherModerator.name());
        fetch(moderator,"/api/claims/"+id).andExpect(status().isForbidden());
    }

    private String reportClaim(Client client,String id,String reason) throws Exception {
        mutation(client,"/api/claims/"+id+"/report-dispute",Map.of("reason",reason),key()).andExpect(status().is2xxSuccessful());
        return jdbc.queryForObject("SELECT id FROM reports WHERE claim_id=? ORDER BY created_at DESC LIMIT 1",String.class,id);
    }
    private void resolve(Client client,String report,String resolution,String note) throws Exception {
        JsonNode current=fetchJson(client,"/api/moderation/reports/"+report);
        Map<String,Object> body=Map.of("resolution",resolution,"note",note,"version",current.path("version").asLong());
        String requestKey=key();
        mutation(client,"/api/moderation/reports/"+report+"/resolve",body,requestKey).andExpect(status().is2xxSuccessful());
        long auditCount=count("SELECT COUNT(*) FROM moderation_audit WHERE report_id=?",report);
        mutation(client,"/api/moderation/reports/"+report+"/resolve",body,requestKey).andExpect(status().is2xxSuccessful());
        assertThat(count("SELECT COUNT(*) FROM moderation_audit WHERE report_id=?",report)).isEqualTo(auditCount);
    }

    private Client client(String label,String role) {
        String name="t19_"+label+"_"+UUID.randomUUID().toString().substring(0,8);
        jdbc.update("INSERT INTO accounts(id,username,username_key,display_name,password_hash,role,status,campus_verified,failed_attempts,created_at) VALUES(?,?,?,?,?,?,'ACTIVE',FALSE,0,?)",
                UUID.randomUUID().toString(),name,name,label,"not-used-by-security-test",role,"2026-11-01T00:00:00Z");
        return new Client(name,role);
    }
    private org.springframework.test.web.servlet.ResultActions fetch(Client client,String url) throws Exception {
        return mvc.perform(get(url).with(user(client.name()).roles(client.role())));
    }
    private JsonNode fetchJson(Client client,String url) throws Exception { return read(fetch(client,url).andExpect(status().isOk()).andReturn()); }
    private org.springframework.test.web.servlet.ResultActions mutation(Client client,String url,Map<String,Object> body,String key) throws Exception {
        MockHttpServletRequestBuilder request=post(url).with(user(client.name()).roles(client.role())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        if(key!=null) request.header("Idempotency-Key",key);
        return mvc.perform(request);
    }
    private Map<String,Object> versioned(Client client,String id,Map<String,Object> additions) throws Exception {
        JsonNode current=fetchJson(client,"/api/claims/"+id);
        Map<String,Object> body=new LinkedHashMap<>(additions); body.put("version",current.path("version").asLong()); return body;
    }
    private Map<String,Object> confirmationBody(Client client,String id) throws Exception {
        JsonNode current=fetchJson(client,"/api/claims/"+id);
        return Map.of("version",current.path("version").asLong(),"approvalRound",current.path("approvalRound").asInt());
    }
    private org.springframework.test.web.servlet.ResultActions action(Client client,String id,String action,Map<String,Object> additions) throws Exception {
        return mutation(client,"/api/claims/"+id+"/"+action,"confirm-return".equals(action)?confirmationBody(client,id):versioned(client,id,additions),key());
    }
    private String submit(Client client,String found,String lost) throws Exception {
        return read(mutation(client,"/api/notices/"+found+"/claims",submitBody(lost,"Private distinctive engraving below the blue bottle cap."),key()).andExpect(status().is2xxSuccessful()).andReturn()).path("id").asText();
    }
    private Map<String,Object> submitBody(String lost,String evidence) { Map<String,Object> body=new LinkedHashMap<>(); body.put("lostNoticeId",lost);body.put("evidence",evidence);return body; }
    private String notice(Client client,String type) throws Exception {
        Map<String,Object> body=new LinkedHashMap<>(); body.put("type",type);body.put("title","Blue bottle private workflow fixture");body.put("category","BOTTLE");
        body.put("occurrenceDate",LocalDate.now(ZoneId.of("Asia/Macau")).toString());body.put("approximate",false);body.put("area","LIBRARY");body.put("description","A fictional blue bottle for independent workflow regression tests.");body.put("retainedPhotoIds",List.of());
        return read(mvc.perform(multipart("/api/notices").file(new MockMultipartFile("notice","notice.json","application/json",mapper.writeValueAsBytes(body)))
                .with(user(client.name()).roles(client.role())).with(csrf()).header("Idempotency-Key",key())).andExpect(status().isCreated()).andReturn()).path("id").asText();
    }
    private String state(String notice) { return jdbc.queryForObject("SELECT status FROM notices WHERE id=?",String.class,notice); }
    private long count(String query,Object...args) { return jdbc.queryForObject(query,Long.class,args); }
    private String key() { return UUID.randomUUID().toString(); }
    private JsonNode read(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
}
