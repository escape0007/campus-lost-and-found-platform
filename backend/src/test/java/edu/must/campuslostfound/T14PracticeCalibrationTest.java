package edu.must.campuslostfound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
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
 * Practice-set calibration for REQ-1.5/1.6 and MAI-01.
 * Dataset practice-2026-11-01 only. The reserved 20 matched and 20 unmatched evaluation cases are not loaded.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:t14-practice;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class T14PracticeCalibrationTest {
    static final String DATASET = "practice-2026-11-01";
    static final List<String> PRACTICE_CODES = List.of(
            "P-SIM-LOST", "P-SIM-HIT", "P-SIM-APPROX", "P-SIM-NEAR", "P-SIM-FAR", "P-SIM-WRONG",
            "P-MISS-BAG-LOST", "P-MISS-BAG-FOUND", "P-MISS-VAGUE-LOST", "P-MISS-VAGUE-FOUND",
            "P-IGN", "P-REOPEN", "P-PREF");
    private static final String PASSWORD = "t14-practice-password-2026";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AlertService alerts;

    record BrowserSession(MockHttpSession session, String csrfHeader, String csrfToken, String username) {}

    @BeforeEach
    void clean() {
        for (String table : List.of("alert_event_deliveries", "match_alerts", "ignored_pairs", "match_candidates", "active_reservations",
                "claim_guards", "notice_photos", "notice_revisions", "notice_events", "notice_audit", "idempotency_records", "notices")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void practiceCodesStayOutOfTheReservedEvaluationSet() {
        assertThat(DATASET).isEqualTo("practice-2026-11-01");
        assertThat(PRACTICE_CODES).allMatch(code -> code.startsWith("P-"));
        assertThat(PRACTICE_CODES).noneMatch(code -> code.startsWith("E-") || code.startsWith("R-") || code.contains("RESERVED"));
    }

    @Test
    void practiceSetCoversSimilarityMissingDetailIgnoreReopenAndPreference() throws Exception {
        BrowserSession lostAuthor = registeredClient("p_lost", "练习丢失同学");
        BrowserSession foundAuthor = registeredClient("p_found", "练习拾到同学");
        BrowserSession reader = registeredClient("p_reader", "练习浏览同学");

        String lost = create(lostAuthor, notice("LOST", "蓝色保温瓶", "BOTTLE", "LIBRARY", DAY, false,
                "图书馆三楼自习区遗失蓝色保温瓶，杯盖有白色划痕。"));
        create(lostAuthor, notice("LOST", "灰色帆布袋", "BAG", "LIBRARY", DAY, false,
                "甲座旁落下灰色帆布袋，袋绳已经松开啦呀。"));
        String vagueLost = create(lostAuthor, notice("LOST", "遗失物品一件", "OTHER", "OTHER", DAY, true,
                "具体外观和牌子都没有写清，只能先登记遗失。"));
        String hit = create(foundAuthor, notice("FOUND", "蓝色保温瓶", "BOTTLE", "LIBRARY", DAY, false,
                "图书馆三楼捡到蓝色保温瓶，杯盖有白色划痕。"));
        String approx = create(foundAuthor, notice("FOUND", "蓝色保温瓶", "BOTTLE", "LIBRARY", DAY.minusDays(1), true,
                "图书馆三楼捡到蓝色保温瓶，杯盖有白色划痕。"));
        String near = create(foundAuthor, notice("FOUND", "蓝色保温瓶", "BOTTLE", "TEACHING_BUILDING", DAY, false,
                "教学楼大厅捡到蓝色保温瓶，杯盖有白色划痕。"));
        String far = create(foundAuthor, notice("FOUND", "黑色保温瓶", "BOTTLE", "CANTEEN", DAY.minusDays(2), false,
                "食堂一楼捡到黑色保温瓶，杯身没有明显划痕。"));
        String wrong = create(foundAuthor, notice("FOUND", "蓝色保温瓶", "KEYS", "LIBRARY", DAY, false,
                "图书馆捡到蓝色保温瓶，但公告类别写成钥匙。"));
        String bag = create(foundAuthor, notice("FOUND", "黑色塑料盒", "BAG", "LIBRARY", DAY, false,
                "乙柜内拾得黑色塑料盒，盒盖仍然盖得很严。"));
        String vague = create(foundAuthor, notice("FOUND", "捡到物品一件", "OTHER", "SPORTS_AREA", LocalDate.of(2026, 9, 1), false,
                "现场没有留下品牌或颜色，描述暂时不足了。"));

        long started = System.nanoTime();
        JsonNode page = suggestions(reader, lost);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(page.path("configVersion").asText()).isEqualTo("t14-2026-11-01");
        assertThat(ids(page)).containsSubsequence(hit, approx, near, far, wrong);
        assertThat(confidence(page, hit)).isEqualTo(100);
        assertThat(confidence(page, approx)).isEqualTo(90);
        assertThat(confidence(page, near)).isEqualTo(100);
        assertThat(confidence(page, far)).isEqualTo(75);
        assertThat(confidence(page, wrong)).isEqualTo(65);
        assertThat(material(page, hit)).isEqualTo(1);
        assertThat(material(page, far)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT material_version FROM notices WHERE id=?", Long.class, lost)).isEqualTo(1L);

        JsonNode vaguePage = suggestions(reader, vagueLost);
        assertThat(confidence(vaguePage, vague)).isEqualTo(20);
        assertThat(vaguePage.path("items").toString()).contains("unspecified category");
        assertThat(alertVersions(lostAuthor, vague)).isEmpty();
        assertThat(alertVersions(lostAuthor, bag)).isEmpty();
        assertThat(alertVersions(lostAuthor, far)).isEmpty();
        assertThat(alertVersions(lostAuthor, wrong)).isEmpty();
        assertThat(alertVersions(lostAuthor, hit)).containsExactly(1L);
        assertThat(alertVersions(lostAuthor, near)).containsExactly(1L);
        assertThat(alertVersions(lostAuthor, approx)).containsExactly(1L);
        assertThat(alertVersions(foundAuthor, hit)).containsExactly(1L);
        JsonNode hitAlert = alertItem(alerts(lostAuthor), hit, 1);
        assertThat(hitAlert.path("lostMaterialVersion").asLong()).isEqualTo(1);
        assertThat(hitAlert.path("confidence").asInt()).isEqualTo(100);
        assertThat(hitAlert.path("configVersion").isMissingNode()).isTrue();
        assertThat(alerts(lostAuthor).path("configVersion").asText()).isEqualTo("t14-2026-11-01");

        assertThat(ids(suggestions(reader, lost))).contains(hit);
        ignore(lostAuthor, lost, hit);
        edit(foundAuthor, hit, notice("FOUND", "蓝色保温瓶", "BOTTLE", "LIBRARY", DAY, false,
                "图书馆三楼捡到蓝色保温瓶，杯盖有白色划痕，绳扣已换新。"));
        assertThat(jdbc.queryForObject("SELECT material_version FROM notices WHERE id=?", Long.class, hit)).isEqualTo(2L);
        assertThat(ids(suggestions(lostAuthor, lost))).doesNotContain(hit);
        assertThat(ids(suggestions(lostAuthor, hit))).doesNotContain(lost);
        assertThat(alertVersions(lostAuthor, hit)).containsExactly(1L);
        assertThat(alertVersions(foundAuthor, hit)).containsExactly(1L, 2L);
        assertThat(ids(suggestions(reader, lost))).contains(hit);
        assertThat(noticeIds(search(lostAuthor))).contains(hit);

        close(foundAuthor, hit);
        assertThat(ids(suggestions(reader, lost))).doesNotContain(hit);
        JsonNode closed = read(mvc.perform(get("/api/notices/" + hit).session(reader.session())).andExpect(status().isOk()).andReturn());
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
        jdbc.update("UPDATE notices SET status='OPEN' WHERE id=?", hit);
        assertThat(ids(suggestions(reader, lost))).contains(hit);
        assertThat(ids(suggestions(lostAuthor, lost))).doesNotContain(hit);
        assertThat(alertVersions(foundAuthor, hit)).containsExactly(1L, 2L);
        assertThat(alertVersions(lostAuthor, hit)).containsExactly(1L);
        assertThat(jdbc.queryForObject("SELECT material_version FROM notices WHERE id=?", Long.class, hit)).isEqualTo(2L);

        JsonNode manual = savePreference(lostAuthor, "MANUAL_CHECKING", preference(lostAuthor).path("version").asLong());
        edit(foundAuthor, near, notice("FOUND", "蓝色保温瓶", "BOTTLE", "TEACHING_BUILDING", DAY, false,
                "教学楼大厅捡到蓝色保温瓶，杯盖有白色划痕，已改一次。"));
        assertThat(jdbc.queryForObject("SELECT material_version FROM notices WHERE id=?", Long.class, near)).isEqualTo(2L);
        assertThat(alertVersions(lostAuthor, near)).containsExactly(1L);
        assertThat(alertVersions(foundAuthor, near)).containsExactly(1L, 2L);
        assertThat(ids(suggestions(lostAuthor, lost))).contains(near);
        assertThat(alerts(lostAuthor).path("message").asText()).contains("自行查看");

        JsonNode enabled = savePreference(lostAuthor, "NEW_MATCH_ALERTS", manual.path("version").asLong());
        assertThat(enabled.path("mode").asText()).isEqualTo("NEW_MATCH_ALERTS");
        String duringManual = latestEvent(near, "MATERIAL_CHANGED");
        alerts.replay(duringManual);
        assertThat(alertVersions(lostAuthor, near)).containsExactly(1L);
        edit(foundAuthor, near, notice("FOUND", "蓝色保温瓶", "BOTTLE", "TEACHING_BUILDING", DAY, false,
                "教学楼大厅捡到蓝色保温瓶，杯盖有白色划痕，已改两次。"));
        assertThat(jdbc.queryForObject("SELECT material_version FROM notices WHERE id=?", Long.class, near)).isEqualTo(3L);
        assertThat(alertVersions(lostAuthor, near)).containsExactly(1L, 3L);
        assertThat(alertVersions(foundAuthor, near)).containsExactly(1L, 2L, 3L);
        assertThat(trigger(latestEvent(near, "MATERIAL_CHANGED"))).isGreaterThan(enableOrder(lostAuthor.username()));
    }

    private JsonNode suggestions(BrowserSession reader, String id) throws Exception {
        return read(mvc.perform(get("/api/notices/" + id + "/suggestions").session(reader.session())).andExpect(status().isOk()).andReturn());
    }

    private List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("noticeId").asText()));
        return ids;
    }

    private int confidence(JsonNode page, String noticeId) {
        for (JsonNode item : page.path("items")) if (item.path("noticeId").asText().equals(noticeId)) return item.path("confidence").asInt();
        throw new AssertionError("Missing suggestion " + noticeId);
    }

    private long material(JsonNode page, String noticeId) {
        for (JsonNode item : page.path("items")) if (item.path("noticeId").asText().equals(noticeId)) return item.path("materialVersion").asLong();
        throw new AssertionError("Missing suggestion " + noticeId);
    }

    private List<Long> alertVersions(BrowserSession client, String foundId) {
        return jdbc.queryForList("SELECT found_material_version FROM match_alerts WHERE recipient_id=? AND found_id=? ORDER BY found_material_version",
                Long.class, client.username(), foundId);
    }

    private JsonNode alertItem(JsonNode page, String foundId, long foundMaterialVersion) {
        for (JsonNode item : page.path("items")) {
            if (item.path("foundId").asText().equals(foundId) && item.path("foundMaterialVersion").asLong() == foundMaterialVersion) return item;
        }
        throw new AssertionError("Missing alert " + foundId + " v" + foundMaterialVersion);
    }

    private JsonNode alerts(BrowserSession client) throws Exception {
        return read(mvc.perform(get("/api/alerts").session(client.session())).andExpect(status().isOk()).andReturn());
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

    private JsonNode search(BrowserSession client) throws Exception {
        return read(mvc.perform(get("/api/notices").param("scope", "available").session(client.session())).andExpect(status().isOk()).andReturn());
    }

    private List<String> noticeIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asText()));
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
                        .content(mapper.writeValueAsBytes(Map.of("version", current.path("version").asLong(), "reason", "Practice archive before the reopen check."))))
                .andExpect(status().isOk());
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
