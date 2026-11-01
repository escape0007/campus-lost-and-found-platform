package edu.must.campuslostfound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T08 acceptance checks against HTTP and persisted outcomes. Claim guards and
 * reservations are fixtures for later claim modules, not claims of end-to-end
 * claim implementation or the six-person usability acceptance test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoticeApiIntegrationTest {
    private static final ZoneId MACAU = ZoneId.of("Asia/Macau");
    private static final String PRIVATE_MARKER = "T08_PRIVATE_IMAGE_METADATA";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanNoticeData() {
        // Keep provisioned account fixtures; each scenario owns all notice data.
        for (String table : List.of("alert_event_deliveries", "match_alerts", "ignored_pairs", "match_candidates", "active_reservations", "claim_guards", "notice_photos",
                "notice_revisions", "notice_events", "notice_audit", "idempotency_records", "notices")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void authenticatedSessionAndOptionVocabularyAreAvailable() throws Exception {
        mvc.perform(get("/api/session")).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                .andExpect(jsonPath("$.csrfHeaderName").isNotEmpty());
        mvc.perform(get("/api/session").with(user("alice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("alice"));
        JsonNode options = read(mvc.perform(get("/api/options").with(user("alice")))
                .andExpect(status().isOk()).andReturn());
        assertThat(options.path("today").asText()).isEqualTo(LocalDate.now(MACAU).toString());
        assertThat(values(options.path("categories"))).containsExactlyInAnyOrder(
                "KEYS", "UMBRELLA", "BOTTLE", "BAG", "ELECTRONICS", "OTHER");
        assertThat(values(options.path("areas"))).containsExactlyInAnyOrder(
                "LIBRARY", "TEACHING_BUILDING", "CANTEEN", "DORMITORY", "SPORTS_AREA", "OTHER");
    }

    @Test
    void realFormLoginCreatesSessionUsableForCsrfProtectedPublication() throws Exception {
        MvcResult login = mvc.perform(formLogin("/api/login").user("alice").password("campus-demo-2026"))
                .andExpect(authenticated().withUsername("alice")).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        JsonNode sessionView = read(mvc.perform(get("/api/session").session(session))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(writeRequest(null, payload(), UUID.randomUUID().toString())
                        .session(session)
                        .header(sessionView.path("csrfHeaderName").asText(),
                                sessionView.path("csrfToken").asText()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void createEditAndArchiveKeepIdentityAndCompleteRevisionHistory() throws Exception {
        JsonNode created = create("alice", payload());
        String id = created.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(created.path("version").asLong()).isEqualTo(1);
        assertThat(created.path("materialVersion").asLong()).isEqualTo(1);
        assertThat(created.path("canEdit").asBoolean()).isTrue();

        Map<String, Object> changed = payload();
        changed.put("title", "图书馆遗失蓝色保温瓶");
        changed.put("version", 1);
        JsonNode edited = edit("alice", id, changed);
        assertThat(edited.path("id").asText()).isEqualTo(id);
        assertThat(edited.path("version").asLong()).isEqualTo(2);
        assertThat(edited.path("materialVersion").asLong()).isEqualTo(2);
        assertThat(edited.path("history").size()).isEqualTo(2);
        assertThat(edited.path("createdAt").asText()).isEqualTo(created.path("createdAt").asText());

        JsonNode archived = close("alice", id, 2, "重复公告，已通过其他渠道处理");
        assertThat(archived.path("id").asText()).isEqualTo(id);
        assertThat(archived.path("status").asText()).isEqualTo("CLOSED");
        assertThat(archived.path("version").asLong()).isEqualTo(3);
        assertThat(archived.path("canEdit").asBoolean()).isFalse();
        assertThat(archived.path("canClose").asBoolean()).isFalse();
        assertThat(archived.path("history").size()).isEqualTo(3);
        assertThat(archived.path("history").toString()).contains("重复公告，已通过其他渠道处理");
        assertThat(list("alice", "available").path("items").size()).isZero();
        assertThat(list("alice", "mine").path("items").get(0).path("id").asText()).isEqualTo(id);
        assertThat(count("notices")).isEqualTo(1);
        assertThat(count("notice_revisions")).isEqualTo(3);
        assertThat(count("notice_events")).isEqualTo(3);
    }

    @Test
    void unicodeLengthsUseCharactersAndApproximateDatesRemainExplicit() throws Exception {
        Map<String, Object> valid = payload();
        valid.put("title", "蓝色保温水瓶");
        valid.put("description", "物".repeat(20));
        valid.put("approximate", true);
        assertThat(create("alice", valid).path("approximate").asBoolean()).isTrue();
        valid.put("title", "瓶".repeat(4));
        invalidPublication(valid, "title");
        valid.put("title", "瓶".repeat(81));
        invalidPublication(valid, "title");
        valid.put("title", "瓶".repeat(5));
        valid.put("description", "物".repeat(19));
        invalidPublication(valid, "description");
        valid.put("description", "物".repeat(1001));
        invalidPublication(valid, "description");
    }

    @Test
    void supplementaryUnicodeDoesNotConsumeTwoCharacterPositions() throws Exception {
        Map<String, Object> body = payload();
        body.put("title", "🔑".repeat(80));
        body.put("description", "📦".repeat(1000));
        create("alice", body);
        body.put("title", "🔑".repeat(81));
        invalidPublication(body, "title");
    }

    @Test
    void futureDatesAndUnknownControlledValuesAreRejected() throws Exception {
        Map<String, Object> body = payload();
        body.put("occurrenceDate", LocalDate.now(MACAU).plusDays(1).toString());
        invalidPublication(body, "occurrenceDate");
        body = payload();
        body.put("occurrenceDate", "2026-02-30");
        invalidPublication(body, "occurrenceDate");
        body = payload();
        body.put("category", "MADE_UP_CATEGORY");
        invalidPublication(body, "category");
        body = payload();
        body.put("area", "PRECISE_ROOM_101");
        invalidPublication(body, "area");
        body = payload();
        body.put("type", "SOLD");
        invalidPublication(body, "type");
    }

    @Test
    void absentRequiredFieldsProduceValidationErrorsInsteadOfServerErrors() throws Exception {
        for (String field : List.of("type", "title", "category", "occurrenceDate", "area", "description")) {
            Map<String, Object> body = payload();
            body.put(field, null);
            invalidPublication(body, field);
        }
        assertThat(count("notices")).isZero();
    }

    @Test
    void myListIsPaginatedAndExcludesOtherAuthorsWhileAvailableUsesPermittedStates() throws Exception {
        for (int i = 0; i < 21; i++) create("alice", payload());
        String otherId = create("bob", payload()).path("id").asText();
        JsonNode first = list("alice", "mine");
        assertThat(first.path("items").size()).isEqualTo(20);
        assertThat(first.path("totalElements").asLong()).isEqualTo(21);
        assertThat(first.path("page").asInt()).isZero();
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        JsonNode second = read(mvc.perform(get("/api/notices").param("scope", "mine").param("page", "1").with(user("alice")))
                .andExpect(status().isOk()).andReturn());
        assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(second.path("hasNext").asBoolean()).isFalse();
        assertThat(first.path("items").toString() + second.path("items").toString()).doesNotContain(otherId);
        jdbc.update("UPDATE notices SET status='CLAIM_UNDER_REVIEW' WHERE id=?", otherId);
        assertThat(list("bob", "available").path("totalElements").asLong()).isEqualTo(22);
        jdbc.update("UPDATE notices SET status='AWAITING_COLLECTION' WHERE id=?", otherId);
        assertThat(list("bob", "available").path("totalElements").asLong()).isEqualTo(21);
    }

    @Test
    void missingAuthenticationCsrfAndForeignOwnershipCannotMutateNotice() throws Exception {
        mvc.perform(get("/api/notices")).andExpect(status().isUnauthorized());
        mvc.perform(writeRequest(null, payload(), UUID.randomUUID().toString()).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(writeRequest(null, payload(), UUID.randomUUID().toString()).with(user("alice")))
                .andExpect(status().isForbidden());
        JsonNode created = create("alice", payload());
        String id = created.path("id").asText();
        Map<String, Object> change = payload();
        change.put("version", 1);
        mvc.perform(writeRequest(id, change, UUID.randomUUID().toString()).with(user("bob")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/notices/{id}/close", id).with(user("bob")).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version", 1, "reason", "越权关闭"))))
                .andExpect(status().isForbidden());
        assertThat(getNotice("alice", id).path("version").asLong()).isEqualTo(1);
        assertThat(count("notice_revisions")).isEqualTo(1);
    }

    @Test
    void forgedServerControlledFieldsAreRejected() throws Exception {
        Map<String, Object> body = payload();
        body.put("authorId", "bob");
        body.put("status", "RETURNED");
        body.put("materialVersion", 99);
        body.put("hidden", true);
        mvc.perform(writeRequest(null, body, UUID.randomUUID().toString()).with(user("alice")).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(count("notices")).isZero();
    }

    @Test
    void staleEditAndStaleCloseReturnConflictWithoutPartialWrites() throws Exception {
        String id = create("alice", payload()).path("id").asText();
        Map<String, Object> first = payload();
        first.put("title", "已更新的蓝色保温瓶公告");
        first.put("version", 1);
        edit("alice", id, first);
        first.put("title", "旧页面不能覆盖的新标题");
        mvc.perform(writeRequest(id, first, UUID.randomUUID().toString()).with(user("alice")).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/notices/{id}/close", id).with(user("alice")).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content("{\"version\":1,\"reason\":\"旧页面关闭\"}"))
                .andExpect(status().isConflict());
        assertThat(getNotice("alice", id).path("title").asText()).isEqualTo("已更新的蓝色保温瓶公告");
        assertThat(count("notice_revisions")).isEqualTo(2);
        assertThat(count("notice_events")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "HELD", "APPROVED"})
    void unresolvedClaimsLockBothFoundAndLinkedLostNotice(String claimStatus) throws Exception {
        Map<String, Object> foundBody = payload();
        foundBody.put("type", "FOUND");
        String foundId = create("alice", foundBody).path("id").asText();
        String lostId = create("bob", payload()).path("id").asText();
        guard(foundId, lostId, claimStatus);
        for (String id : List.of(foundId, lostId)) {
            String author = id.equals(foundId) ? "alice" : "bob";
            JsonNode view = getNotice(author, id);
            assertThat(view.path("canEdit").asBoolean()).isFalse();
            assertThat(view.path("canClose").asBoolean()).isFalse();
            assertThat(view.path("lockReason").asText()).isNotBlank();
            Map<String, Object> edit = payload();
            edit.put("type", id.equals(foundId) ? "FOUND" : "LOST");
            edit.put("version", 1);
            mvc.perform(writeRequest(id, edit, UUID.randomUUID().toString()).with(user(author)).with(csrf()))
                    .andExpect(status().isConflict());
            mvc.perform(post("/api/notices/{id}/close", id).with(user(author)).with(csrf())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType("application/json").content("{\"version\":1,\"reason\":\"案件未解决\"}"))
                    .andExpect(status().isConflict());
        }
        assertThat(count("notice_revisions")).isEqualTo(2);
    }

    @Test
    void activeReservationExcludesNoticeFromAvailableAndPreventsChanges() throws Exception {
        String id = create("alice", payload()).path("id").asText();
        jdbc.update("INSERT INTO active_reservations(notice_id, claim_id) VALUES (?, ?)", id, UUID.randomUUID().toString());
        assertThat(list("bob", "available").path("items").size()).isZero();
        assertThat(list("alice", "mine").path("items").size()).isEqualTo(1);
        assertThat(getNotice("alice", id).path("canEdit").asBoolean()).isFalse();
        Map<String, Object> changed = payload();
        changed.put("version", 1);
        mvc.perform(writeRequest(id, changed, UUID.randomUUID().toString()).with(user("alice")).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/notices/{id}/close", id).with(user("alice")).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content("{\"version\":1,\"reason\":\"预约中不能归档\"}"))
                .andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSED", "RETURNED"})
    void terminalNoticesAreReadOnlyAndImagesRemainSubjectToAuthentication(String terminal) throws Exception {
        JsonNode created = create("alice", payload(), png("photo.png"));
        String id = created.path("id").asText();
        String imageUrl = created.path("photos").get(0).path("url").asText();
        jdbc.update("UPDATE notices SET status=? WHERE id=?", terminal, id);
        JsonNode view = getNotice("alice", id);
        assertThat(view.path("canEdit").asBoolean()).isFalse();
        assertThat(view.path("canClose").asBoolean()).isFalse();
        assertThat(list("bob", "available").path("items").size()).isZero();
        mvc.perform(get(imageUrl)).andExpect(status().isUnauthorized());
        mvc.perform(get(imageUrl).with(user("alice"))).andExpect(status().isOk());
        Map<String, Object> changed = payload();
        changed.put("version", 1);
        mvc.perform(writeRequest(id, changed, UUID.randomUUID().toString()).with(user("alice")).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void hiddenNoticeAndCopiedImageUrlDoNotLeakToAnotherStudent() throws Exception {
        JsonNode created = create("alice", payload(), png("photo.png"));
        String id = created.path("id").asText();
        String photoUrl = created.path("photos").get(0).path("url").asText();
        jdbc.update("UPDATE notices SET hidden=true WHERE id=?", id);
        mvc.perform(get("/api/notices/{id}", id).with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(get(photoUrl).with(user("bob"))).andExpect(status().isNotFound());
        assertThat(list("bob", "available").path("items").size()).isZero();
        mvc.perform(get("/api/notices/{id}", id).with(user("alice"))).andExpect(status().isNotFound());
        mvc.perform(get(photoUrl).with(user("alice"))).andExpect(status().isNotFound());
        Map<String, Object> changed = payload();
        changed.put("version", 1);
        mvc.perform(writeRequest(id, changed, UUID.randomUUID().toString()).with(user("alice")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyActualJpegAndPngWithinQuantityAndSizeLimitsAreAccepted() throws Exception {
        MockMultipartFile disguised = new MockMultipartFile("photos", "fake.png", "image/png", "not-an-image".getBytes(StandardCharsets.UTF_8));
        invalidPhoto(disguised);
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream gif = new ByteArrayOutputStream();
        ImageIO.write(image, "gif", gif);
        invalidPhoto(new MockMultipartFile("photos", "fake.jpg", "image/jpeg", gif.toByteArray()));
        invalidPhoto(new MockMultipartFile("photos", "unsupported.gif", "image/gif", gif.toByteArray()));
        invalidPhoto(new MockMultipartFile("photos", "huge.png", "image/png",
                java.util.Arrays.copyOf(pngBytes(false), 5 * 1024 * 1024 + 1)));
        mvc.perform(writeRequest(null, payload(), UUID.randomUUID().toString(), png("1.png"), png("2.png"), png("3.png"), png("4.png"))
                        .with(user("alice")).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(count("notices")).isZero();
        JsonNode accepted = create("alice", payload(), png("1.png"), png("2.png"), jpeg("3.jpg"));
        assertThat(accepted.path("photos").size()).isEqualTo(3);
    }

    @Test
    void imageIsReencodedWithoutSourceMetadataOrUnsafeInlineHeaders() throws Exception {
        byte[] source = pngBytes(true);
        assertThat(new String(source, StandardCharsets.ISO_8859_1)).contains(PRIVATE_MARKER);
        JsonNode created = create("alice", payload(), new MockMultipartFile("photos", "camera.png", "image/png", source));
        String photoUrl = created.path("photos").get(0).path("url").asText();
        MvcResult download = mvc.perform(get(photoUrl).with(user("alice"))).andExpect(status().isOk()).andReturn();
        assertThat(download.getResponse().getContentType()).startsWith("image/png");
        assertThat(download.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(new String(download.getResponse().getContentAsByteArray(), StandardCharsets.ISO_8859_1)).doesNotContain(PRIVATE_MARKER);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(download.getResponse().getContentAsByteArray()))).isNotNull();
    }

    @Test
    void changingOnlyPhotosIncrementsRevisionButNotMaterialVersion() throws Exception {
        JsonNode created = create("alice", payload(), png("old.png"));
        String id = created.path("id").asText();
        Map<String, Object> change = payload();
        change.put("version", 1);
        change.put("retainedPhotoIds", List.of());
        JsonNode changed = edit("alice", id, change, jpeg("new.jpg"));
        assertThat(changed.path("version").asLong()).isEqualTo(2);
        assertThat(changed.path("materialVersion").asLong()).isEqualTo(1);
        assertThat(changed.path("photos").size()).isEqualTo(1);
        assertThat(changed.path("photos").get(0).path("contentType").asText()).isEqualTo("image/jpeg");
        mvc.perform(get(created.path("photos").get(0).path("url").asText()).with(user("alice")))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotAttachAnotherNoticesPhotoByRetainedPhotoId() throws Exception {
        JsonNode first = create("alice", payload(), png("first.png"));
        JsonNode second = create("bob", payload(), png("second.png"));
        Map<String, Object> changed = payload();
        changed.put("version", 1);
        changed.put("retainedPhotoIds", List.of(second.path("photos").get(0).path("id").asText()));
        mvc.perform(writeRequest(first.path("id").asText(), changed, UUID.randomUUID().toString()).with(user("alice")).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(getNotice("alice", first.path("id").asText()).path("version").asLong()).isEqualTo(1);
    }

    @Test
    void retainedAndNewPhotosShareTheThreePhotoLimit() throws Exception {
        JsonNode created = create("alice", payload(), png("1.png"), png("2.png"), png("3.png"));
        List<String> ids = new ArrayList<>();
        created.path("photos").forEach(photo -> ids.add(photo.path("id").asText()));
        Map<String, Object> change = payload();
        change.put("version", 1);
        change.put("retainedPhotoIds", ids);
        mvc.perform(writeRequest(created.path("id").asText(), change, UUID.randomUUID().toString(), png("4.png"))
                        .with(user("alice")).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(getNotice("alice", created.path("id").asText()).path("photos").size()).isEqualTo(3);
        assertThat(count("notice_revisions")).isEqualTo(1);
    }

    @Test
    void idempotencyKeyReplaysOnceAndRejectsChangedRequestPayload() throws Exception {
        String key = UUID.randomUUID().toString();
        JsonNode first = read(mvc.perform(writeRequest(null, payload(), key).with(user("alice")).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
        JsonNode retry = read(mvc.perform(writeRequest(null, payload(), key).with(user("alice")).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
        assertThat(retry).isEqualTo(first);
        assertThat(count("notices")).isEqualTo(1);
        assertThat(count("notice_revisions")).isEqualTo(1);
        assertThat(count("notice_events")).isEqualTo(1);
        Map<String, Object> different = payload();
        different.put("title", "不同请求不能复用同一幂等键");
        mvc.perform(writeRequest(null, different, key).with(user("alice")).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(count("notices")).isEqualTo(1);
    }

    @Test
    void idempotentEditAndCloseDoNotDuplicateHistoryOrEvents() throws Exception {
        String id = create("alice", payload()).path("id").asText();
        Map<String, Object> change = payload();
        change.put("version", 1);
        change.put("title", "幂等修改后的蓝色保温水瓶");
        String editKey = UUID.randomUUID().toString();
        JsonNode first = read(mvc.perform(writeRequest(id, change, editKey).with(user("alice")).with(csrf()))
                .andExpect(status().isOk()).andReturn());
        JsonNode retry = read(mvc.perform(writeRequest(id, change, editKey).with(user("alice")).with(csrf()))
                .andExpect(status().isOk()).andReturn());
        assertThat(retry).isEqualTo(first);
        String closeKey = UUID.randomUUID().toString();
        byte[] body = mapper.writeValueAsBytes(Map.of("version", 2, "reason", "已自行处理，关闭公告"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/notices/{id}/close", id).with(user("alice")).with(csrf())
                            .header("Idempotency-Key", closeKey).contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
        }
        assertThat(count("notice_revisions")).isEqualTo(3);
        assertThat(count("notice_events")).isEqualTo(3);
    }

    @Test
    void revisionWriteFailureRollsBackNoticePhotoAuditEventAndIdempotencyWrites() throws Exception {
        JsonNode created = create("alice", payload(), png("original.png"));
        String id = created.path("id").asText();
        String originalPhotoId = created.path("photos").get(0).path("id").asText();
        jdbc.update("INSERT INTO notice_revisions(notice_id,version,action,actor,occurred_at,reason,changed_fields,status,snapshot_json) "
                + "SELECT notice_id,2,action,actor,occurred_at,reason,changed_fields,status,snapshot_json "
                + "FROM notice_revisions WHERE notice_id=? AND version=1", id);
        Map<String, Object> change = payload();
        change.put("version", 1);
        change.put("title", "失败事务不能留下修改后的新标题");
        String key = UUID.randomUUID().toString();
        MockMultipartFile replacement = jpeg("replacement.jpg");
        mvc.perform(writeRequest(id, change, key, replacement).with(user("alice")).with(csrf()))
                .andExpect(status().isServiceUnavailable());
        JsonNode after = getNotice("alice", id);
        assertThat(after.path("title").asText()).isEqualTo(created.path("title").asText());
        assertThat(after.path("version").asLong()).isEqualTo(1);
        assertThat(after.path("photos").size()).isEqualTo(1);
        assertThat(after.path("photos").get(0).path("id").asText()).isEqualTo(originalPhotoId);
        assertThat(count("notice_photos")).isEqualTo(1);
        assertThat(count("notice_events")).isEqualTo(1);
        assertThat(count("notice_audit")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
        jdbc.update("DELETE FROM notice_revisions WHERE notice_id=? AND version=2", id);
        mvc.perform(writeRequest(id, change, key, replacement).with(user("alice")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void outboxWriteFailureAlsoRollsBackThePrecedingRevision() throws Exception {
        JsonNode created = create("alice", payload());
        String id = created.path("id").asText();
        String injectedId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO notice_events(id,notice_id,kind,version,material_version,trigger_order,created_at) "
                        + "SELECT ?,notice_id,kind,2,material_version,trigger_order+10000,created_at FROM notice_events WHERE notice_id=? AND version=1",
                injectedId, id);
        Map<String, Object> change = payload();
        change.put("version", 1);
        change.put("title", "事件失败时同样必须回滚的新标题");
        String key = UUID.randomUUID().toString();
        mvc.perform(writeRequest(id, change, key).with(user("alice")).with(csrf()))
                .andExpect(status().isServiceUnavailable());
        assertThat(getNotice("alice", id).path("version").asLong()).isEqualTo(1);
        assertThat(getNotice("alice", id).path("title").asText()).isEqualTo(created.path("title").asText());
        assertThat(count("notice_revisions")).isEqualTo(1);
        assertThat(count("notice_audit")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
        jdbc.update("DELETE FROM notice_events WHERE id=?", injectedId);
        mvc.perform(writeRequest(id, change, key).with(user("alice")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void simultaneousEditsFromSameVersionHaveExactlyOneWinner() throws Exception {
        String id = create("alice", payload()).path("id").asText();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String title : List.of("并发编辑甲提交的有效新标题", "并发编辑乙提交的有效新标题")) {
                Callable<Integer> attempt = () -> {
                    Map<String, Object> body = payload();
                    body.put("version", 1);
                    body.put("title", title);
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return mvc.perform(writeRequest(id, body, UUID.randomUUID().toString()).with(user("alice")).with(csrf()))
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(attempt));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(getNotice("alice", id).path("version").asLong()).isEqualTo(2);
            assertThat(count("notice_revisions")).isEqualTo(2);
            assertThat(count("notice_events")).isEqualTo(2);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private Map<String, Object> payload() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "LOST");
        body.put("title", "图书馆蓝色保温水瓶遗失");
        body.put("category", "BOTTLE");
        body.put("occurrenceDate", LocalDate.now(MACAU).minusDays(1).toString());
        body.put("approximate", false);
        body.put("area", "LIBRARY");
        body.put("description", "在图书馆附近遗失蓝色保温瓶，看到相似物品请通过平台联系。" );
        body.put("retainedPhotoIds", List.of());
        return body;
    }

    private JsonNode create(String username, Map<String, Object> body, MockMultipartFile... photos) throws Exception {
        return read(mvc.perform(writeRequest(null, body, UUID.randomUUID().toString(), photos).with(user(username)).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode edit(String username, String id, Map<String, Object> body, MockMultipartFile... photos) throws Exception {
        return read(mvc.perform(writeRequest(id, body, UUID.randomUUID().toString(), photos).with(user(username)).with(csrf()))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode close(String username, String id, long version, String reason) throws Exception {
        return read(mvc.perform(post("/api/notices/{id}/close", id).with(user(username)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version", version, "reason", reason))))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode getNotice(String username, String id) throws Exception {
        return read(mvc.perform(get("/api/notices/{id}", id).with(user(username))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode list(String username, String scope) throws Exception {
        return read(mvc.perform(get("/api/notices").param("scope", scope).param("page", "0").with(user(username)))
                .andExpect(status().isOk()).andReturn());
    }

    private MockMultipartHttpServletRequestBuilder writeRequest(String id, Map<String, Object> body, String key,
                                                                 MockMultipartFile... photos) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(id == null ? "/api/notices" : "/api/notices/" + id);
        request.file(new MockMultipartFile("notice", "notice.json", "application/json", mapper.writeValueAsBytes(body)));
        for (MockMultipartFile photo : photos) request.file(photo);
        if (id != null) request.with(r -> { r.setMethod("PUT"); return r; });
        request.header("Idempotency-Key", key);
        return request;
    }

    private void invalidPublication(Map<String, Object> body, String field) throws Exception {
        mvc.perform(writeRequest(null, body, UUID.randomUUID().toString()).with(user("alice")).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors." + field).isNotEmpty());
    }

    private void invalidPhoto(MockMultipartFile photo) throws Exception {
        mvc.perform(writeRequest(null, payload(), UUID.randomUUID().toString(), photo).with(user("alice")).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    private String guard(String foundId, String lostId, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO claim_guards(id, found_id, lost_id, status) VALUES (?, ?, ?, ?)", id, foundId, lostId, status);
        return id;
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private JsonNode read(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }

    private List<String> values(JsonNode options) {
        List<String> result = new ArrayList<>();
        options.forEach(option -> result.add(option.path("value").asText()));
        return result;
    }

    private MockMultipartFile png(String filename) throws Exception {
        return new MockMultipartFile("photos", filename, "image/png", pngBytes(false));
    }

    private MockMultipartFile jpeg(String filename) throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", bytes);
        return new MockMultipartFile("photos", filename, "image/jpeg", bytes.toByteArray());
    }

    private byte[] pngBytes(boolean includePrivateMetadata) throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            IIOMetadata metadata = writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image), null);
            if (includePrivateMetadata) {
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_png_1.0");
                IIOMetadataNode text = new IIOMetadataNode("tEXt");
                IIOMetadataNode entry = new IIOMetadataNode("tEXtEntry");
                entry.setAttribute("keyword", "Location");
                entry.setAttribute("value", PRIVATE_MARKER);
                text.appendChild(entry);
                root.appendChild(text);
                metadata.setFromTree("javax_imageio_png_1.0", root);
            }
            writer.write(null, new IIOImage(image, null, metadata), null);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
