package edu.must.campuslostfound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T13 alert preference, dedup and delayed retry. REQ-1.6 and the AT-06 rules.
 * The 16-of-20 unmatched-case protocol is not claimed as passed. Claims, returns and moderation are not implemented.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:t13-alerts;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class T13AlertIntegrationTest {
    private static final String PASSWORD = "t13-fictional-password-2026";
    private static final ZoneId MACAU = ZoneId.of("Asia/Macau");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AlertService alerts;

    record BrowserSession(MockHttpSession session, String csrfHeader, String csrfToken, String username) {}

    @BeforeEach
    void clean() {
        for (String table : List.of("alert_event_deliveries", "match_alerts", "ignored_pairs", "match_candidates", "active_reservations", "claim_guards", "notice_photos",
                "notice_revisions", "notice_events", "notice_audit", "idempotency_records", "notices")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void manualModeKeepsHistoryAndReenableDoesNotBackfill() throws Exception {
        BrowserSession lostAuthor = registeredClient("t13_lost", "提醒丢失同学");
        BrowserSession foundAuthor = registeredClient("t13_found", "提醒拾到同学");
        JsonNode proposed = preference(lostAuthor);
        assertThat(proposed.path("mode").asText()).isEqualTo("NEW_MATCH_ALERTS");
        assertThat(proposed.path("usingProposedDefault").asBoolean()).isTrue();
        assertThat(proposed.path("defaultConfirmation").asText()).isEqualTo("PENDING");
        assertThat(proposed.path("defaultNote").asText()).contains("has not confirmed");
        mvc.perform(get("/api/alerts")).andExpect(status().isUnauthorized());

        LocalDate today = LocalDate.now(MACAU);
        String lost = create(lostAuthor, notice("LOST", "蓝色保温瓶 Blue Flask", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：图书馆遗失蓝色保温瓶 Blue Flask，请只通过平台联系。"));
        Instant started = Instant.now();
        String found = create(foundAuthor, notice("FOUND", "蓝色保温瓶 Blue Flask", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：图书馆捡到蓝色保温瓶 Blue Flask，请只通过平台联系。"));
        JsonNode first = alerts(lostAuthor);
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(60));
        assertThat(first.path("configVersion").asText()).isEqualTo("t13-2026-10-25");
        assertThat(first.path("disclaimer").asText()).contains("not prove ownership");
        assertThat(foundVersions(lostAuthor.username())).containsExactly(1L);
        assertThat(foundVersions(foundAuthor.username())).containsExactly(1L);
        JsonNode alert = item(first, 1);
        assertThat(alert.path("confidence").asInt()).isGreaterThanOrEqualTo(70);
        assertThat(alert.path("reason").asText()).contains("相同类别");
        assertThat(alert.path("description").isMissingNode()).isTrue();
        assertThat(Instant.parse(alert.path("createdAt").asText())).isAfterOrEqualTo(started.minusSeconds(5));
        assertThat(Duration.between(started, Instant.parse(alert.path("createdAt").asText()))).isLessThan(Duration.ofSeconds(60));
        String publishEvent = latestEvent(found, "PUBLISHED");
        assertThat(alert.path("triggerOrder").asLong()).isEqualTo(trigger(publishEvent));

        String foundAlert = item(alerts(foundAuthor), 1).path("id").asText();
        mvc.perform(post("/api/alerts/" + foundAlert + "/read").session(lostAuthor.session())
                .header(lostAuthor.csrfHeader(), lostAuthor.csrfToken())).andExpect(status().isNotFound());
        mvc.perform(post("/api/alerts/" + foundAlert + "/read").session(foundAuthor.session())
                .header(foundAuthor.csrfHeader(), foundAuthor.csrfToken())).andExpect(status().isNoContent());
        assertThat(item(alerts(foundAuthor), 1).path("read").asBoolean()).isTrue();

        JsonNode manual = savePreference(lostAuthor, "MANUAL_CHECKING", proposed.path("version").asLong());
        assertThat(manual.path("mode").asText()).isEqualTo("MANUAL_CHECKING");
        assertThat(manual.path("usingProposedDefault").asBoolean()).isFalse();
        edit(foundAuthor, found, notice("FOUND", "蓝色保温瓶 Blue Flask 二", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：图书馆捡到蓝色保温瓶 Blue Flask，描述已实质修改一次。"));
        assertThat(foundVersions(lostAuthor.username())).containsExactly(1L);
        assertThat(foundVersions(foundAuthor.username())).containsExactly(1L, 2L);
        assertThat(alerts(lostAuthor).path("message").asText()).contains("自行查看");
        assertThat(noticeIds(search(lostAuthor))).contains(lost, found);
        assertThat(suggestionIds(lostAuthor, lost)).contains(found);

        JsonNode enabled = savePreference(lostAuthor, "NEW_MATCH_ALERTS", manual.path("version").asLong());
        assertThat(enabled.path("mode").asText()).isEqualTo("NEW_MATCH_ALERTS");
        String manualEvent = latestEvent(found, "MATERIAL_CHANGED");
        long enableOrder = enableOrder(lostAuthor.username());
        assertThat(trigger(manualEvent)).isLessThanOrEqualTo(enableOrder);
        alerts.replay(manualEvent);
        alerts.replay(publishEvent);
        assertThat(foundVersions(lostAuthor.username())).containsExactly(1L);

        edit(foundAuthor, found, notice("FOUND", "蓝色保温瓶 Blue Flask 三", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：图书馆捡到蓝色保温瓶 Blue Flask，描述已实质修改两次。"));
        assertThat(trigger(latestEvent(found, "MATERIAL_CHANGED"))).isGreaterThan(enableOrder);
        assertThat(enableOrder(lostAuthor.username())).isEqualTo(enableOrder);
        assertThat(foundVersions(lostAuthor.username())).containsExactly(1L, 3L);
        assertThat(foundVersions(foundAuthor.username())).containsExactly(1L, 2L, 3L);
        alerts.replay(latestEvent(found, "MATERIAL_CHANGED"));
        assertThat(foundVersions(lostAuthor.username())).containsExactly(1L, 3L);

        long materialVersion = jdbc.queryForObject("SELECT material_version FROM notices WHERE id=?", Long.class, found);
        String updatedAt = jdbc.queryForObject("SELECT updated_at FROM notices WHERE id=?", String.class, found);
        edit(foundAuthor, found, notice("FOUND", "蓝色保温瓶 Blue Flask 三", "BOTTLE", "LIBRARY", today, true,
                "虚构公告：图书馆捡到蓝色保温瓶 Blue Flask，描述已实质修改两次。"));
        assertThat(jdbc.queryForObject("SELECT material_version FROM notices WHERE id=?", Long.class, found)).isEqualTo(materialVersion);
        assertThat(jdbc.queryForObject("SELECT version FROM notices WHERE id=?", Long.class, found)).isGreaterThan(1L);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM notices WHERE id=?", String.class, found)).isNotEqualTo(updatedAt);
        assertThat(latestEvent(found, "NOTICE_UPDATED")).isNotBlank();
        alerts.replay(latestEvent(found, "NOTICE_UPDATED"));
        assertThat(foundVersions(lostAuthor.username())).containsExactly(1L, 3L);

        ignore(lostAuthor, lost, found);
        edit(foundAuthor, found, notice("FOUND", "蓝色保温瓶 Blue Flask 四", "BOTTLE", "LIBRARY", today, true,
                "虚构公告：图书馆捡到蓝色保温瓶 Blue Flask，描述已实质修改三次。"));
        assertThat(foundVersions(lostAuthor.username())).containsExactly(1L, 3L);
        assertThat(foundVersions(foundAuthor.username())).containsExactly(1L, 2L, 3L, 4L);
        assertThat(suggestionIds(lostAuthor, lost)).doesNotContain(found);
        assertThat(suggestionIds(foundAuthor, found)).contains(lost);
        assertThat(noticeIds(search(lostAuthor))).contains(found);

        close(foundAuthor, found);
        assertThat(foundVersions(lostAuthor.username())).containsExactly(1L, 3L);
        assertThat(item(alerts(lostAuthor), 3).path("foundStatus").asText()).isEqualTo("CLOSED");
        JsonNode closed = read(mvc.perform(get("/api/notices/" + found).session(lostAuthor.session())).andExpect(status().isOk()).andReturn());
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
        assertThat(noticeIds(search(lostAuthor))).contains(lost).doesNotContain(found);
        alerts.replay(publishEvent);
        alerts.replay(latestEvent(found, "MATERIAL_CHANGED"));
        assertThat(foundVersions(lostAuthor.username())).containsExactly(1L, 3L);
        mvc.perform(get("/api/notices/" + lost + "/suggestions").session(lostAuthor.session())).andExpect(status().isOk());
    }

    @Test
    void lowConfidenceCreatesNoAlert() throws Exception {
        BrowserSession lostAuthor = registeredClient("t13_low_lost", "低置信丢失");
        BrowserSession foundAuthor = registeredClient("t13_low_found", "低置信拾到");
        LocalDate today = LocalDate.now(MACAU);
        String lost = create(lostAuthor, notice("LOST", "Blue flask lost item", "BOTTLE", "LIBRARY", today, false,
                "Missing blue flask near library shelves only."));
        String found = create(foundAuthor, notice("FOUND", "Black umbrella found", "UMBRELLA", "SPORTS_AREA", today, false,
                "Recovered black umbrella beside running track."));
        assertThat(foundVersions(lostAuthor.username())).isEmpty();
        assertThat(foundVersions(foundAuthor.username())).isEmpty();
        assertThat(suggestionIds(lostAuthor, lost)).contains(found);
    }

    @Test
    void sameAuthorReceivesOneAlertForThePair() throws Exception {
        BrowserSession owner = registeredClient("t13_same", "同一发布同学");
        LocalDate today = LocalDate.now(MACAU);
        create(owner, notice("LOST", "蓝色保温瓶 Blue Flask", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：图书馆遗失蓝色保温瓶 Blue Flask，请只通过平台联系。"));
        create(owner, notice("FOUND", "蓝色保温瓶 Blue Flask", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：图书馆捡到蓝色保温瓶 Blue Flask，请只通过平台联系。"));
        assertThat(foundVersions(owner.username())).containsExactly(1L);
    }

    @Test
    void invalidPreferenceKeepsTheSavedMode() throws Exception {
        BrowserSession client = registeredClient("t13_pref", "偏好同学");
        mvc.perform(get("/api/alert-preference")).andExpect(status().isUnauthorized());
        JsonNode proposed = preference(client);
        JsonNode manual = savePreference(client, "MANUAL_CHECKING", proposed.path("version").asLong());
        assertThat(manual.path("version").asLong()).isEqualTo(proposed.path("version").asLong() + 1);

        MvcResult stale = mvc.perform(put("/api/alert-preference").session(client.session())
                        .header(client.csrfHeader(), client.csrfToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("mode", "NEW_MATCH_ALERTS", "version", proposed.path("version").asLong()))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(stale).path("code").asText()).isEqualTo("CONFLICT");
        assertThat(preference(client).path("mode").asText()).isEqualTo("MANUAL_CHECKING");
        assertThat(preference(client).path("version").asLong()).isEqualTo(manual.path("version").asLong());

        MvcResult invalid = mvc.perform(put("/api/alert-preference").session(client.session())
                        .header(client.csrfHeader(), client.csrfToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("mode", "EMAIL", "version", manual.path("version").asLong()))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(invalid).path("fieldErrors").path("mode").asText()).contains("自行查看");
        JsonNode kept = preference(client);
        assertThat(kept.path("mode").asText()).isEqualTo("MANUAL_CHECKING");
        assertThat(kept.path("version").asLong()).isEqualTo(manual.path("version").asLong());
        assertThat(kept.path("defaultConfirmation").asText()).isEqualTo("PENDING");
    }

    private JsonNode item(JsonNode page, long foundMaterialVersion) {
        for (JsonNode item : page.path("items")) {
            if (item.path("foundMaterialVersion").asLong() == foundMaterialVersion) return item;
        }
        throw new AssertionError("Missing alert for found material version " + foundMaterialVersion);
    }

    private List<Long> foundVersions(String username) {
        return jdbc.queryForList("SELECT found_material_version FROM match_alerts WHERE recipient_id=? ORDER BY found_material_version", Long.class, username);
    }

    private String latestEvent(String noticeId, String kind) {
        return jdbc.queryForObject("SELECT id FROM notice_events WHERE notice_id=? AND kind=? ORDER BY trigger_order DESC LIMIT 1", String.class, noticeId, kind);
    }

    private long trigger(String eventId) {
        return jdbc.queryForObject("SELECT trigger_order FROM notice_events WHERE id=?", Long.class, eventId);
    }

    private long enableOrder(String username) {
        return jdbc.queryForObject("SELECT latest_enable_order FROM match_preferences WHERE user_id=?", Long.class, username);
    }

    private JsonNode preference(BrowserSession client) throws Exception {
        return read(mvc.perform(get("/api/alert-preference").session(client.session())).andExpect(status().isOk()).andReturn());
    }

    private JsonNode savePreference(BrowserSession client, String mode, long version) throws Exception {
        return read(mvc.perform(put("/api/alert-preference").session(client.session())
                        .header(client.csrfHeader(), client.csrfToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("mode", mode, "version", version))))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode alerts(BrowserSession client) throws Exception {
        return read(mvc.perform(get("/api/alerts").session(client.session())).andExpect(status().isOk()).andReturn());
    }

    private JsonNode search(BrowserSession client) throws Exception {
        return read(mvc.perform(get("/api/notices").param("scope", "available").session(client.session())).andExpect(status().isOk()).andReturn());
    }

    private List<String> noticeIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    private List<String> suggestionIds(BrowserSession client, String noticeId) throws Exception {
        JsonNode page = read(mvc.perform(get("/api/notices/" + noticeId + "/suggestions").session(client.session())).andExpect(status().isOk()).andReturn());
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("noticeId").asText()));
        return ids;
    }

    private void ignore(BrowserSession client, String sourceId, String otherId) throws Exception {
        mvc.perform(post("/api/notices/" + sourceId + "/suggestions/" + otherId + "/ignore").session(client.session())
                .header(client.csrfHeader(), client.csrfToken())).andExpect(status().isNoContent());
    }

    private void edit(BrowserSession author, String id, Map<String, Object> body) throws Exception {
        JsonNode current = read(mvc.perform(get("/api/notices/" + id).session(author.session())).andExpect(status().isOk()).andReturn());
        body.put("version", current.path("version").asLong());
        MockMultipartHttpServletRequestBuilder request = multipart("/api/notices/" + id);
        request.with(builder -> { builder.setMethod("PUT"); return builder; });
        request.file(new MockMultipartFile("notice", "notice.json", "application/json", mapper.writeValueAsBytes(body)));
        request.session(author.session());
        request.header(author.csrfHeader(), author.csrfToken());
        request.header("Idempotency-Key", UUID.randomUUID().toString());
        mvc.perform(request).andExpect(status().isOk());
    }

    private void close(BrowserSession author, String id) throws Exception {
        JsonNode current = read(mvc.perform(get("/api/notices/" + id).session(author.session())).andExpect(status().isOk()).andReturn());
        mvc.perform(post("/api/notices/" + id + "/close").session(author.session())
                        .header(author.csrfHeader(), author.csrfToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("version", current.path("version").asLong(), "reason", "Archived after the alert check."))))
                .andExpect(status().isOk());
    }

    private BrowserSession registeredClient(String prefix, String displayName) throws Exception {
        BrowserSession anonymous = anonymousSession();
        String username = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        mvc.perform(post("/api/register").session(anonymous.session())
                        .header(anonymous.csrfHeader(), anonymous.csrfToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", username, "displayName", displayName, "password", PASSWORD))))
                .andExpect(status().isCreated());
        MvcResult login = mvc.perform(post("/api/login").session(anonymous.session())
                        .header(anonymous.csrfHeader(), anonymous.csrfToken())
                        .param("username", username).param("password", PASSWORD))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession authenticated = (MockHttpSession) login.getRequest().getSession(false);
        JsonNode fresh = read(mvc.perform(get("/api/session").session(authenticated)).andExpect(status().isOk()).andReturn());
        return new BrowserSession(authenticated, fresh.path("csrfHeaderName").asText(), fresh.path("csrfToken").asText(), username);
    }

    private BrowserSession anonymousSession() throws Exception {
        MvcResult result = mvc.perform(get("/api/session")).andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        return new BrowserSession((MockHttpSession) result.getRequest().getSession(false),
                body.path("csrfHeaderName").asText(), body.path("csrfToken").asText(), "");
    }

    private String create(BrowserSession client, Map<String, Object> body) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/notices");
        request.file(new MockMultipartFile("notice", "notice.json", "application/json", mapper.writeValueAsBytes(body)));
        request.session(client.session());
        request.header(client.csrfHeader(), client.csrfToken());
        request.header("Idempotency-Key", UUID.randomUUID().toString());
        return read(mvc.perform(request).andExpect(status().isCreated()).andReturn()).path("id").asText();
    }

    private Map<String, Object> notice(String type, String title, String category, String area, LocalDate date, boolean approximate, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("category", category);
        body.put("occurrenceDate", date.toString());
        body.put("approximate", approximate);
        body.put("area", area);
        body.put("description", description);
        body.put("retainedPhotoIds", List.of());
        return body;
    }

    private JsonNode read(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
}
