package edu.must.campuslostfound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T12 ranked suggestions. REQ-1.5, AT-05 rules and the PERF-03 refresh budget.
 * The 20-case volunteer protocol is not claimed as passed. Claims, returns and moderation are not implemented here.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:t12-match;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class T12MatchIntegrationTest {
    private static final String PASSWORD = "t12-fictional-password-2026";
    private static final ZoneId MACAU = ZoneId.of("Asia/Macau");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    record BrowserSession(MockHttpSession session, String csrfHeader, String csrfToken, String username) {}

    @BeforeEach
    void clean() {
        for (String table : List.of("alert_event_deliveries", "match_alerts", "ignored_pairs", "match_candidates", "active_reservations", "claim_guards", "notice_photos",
                "notice_revisions", "notice_events", "notice_audit", "idempotency_records", "notices")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void ranksOpenOppositesAndRefreshesWithinTenSeconds() throws Exception {
        BrowserSession author = registeredClient("match_author", "匹配发布同学");
        BrowserSession reader = registeredClient("match_reader", "匹配浏览同学");
        LocalDate today = LocalDate.now(MACAU);
        String lost = create(author, notice("LOST", "蓝色保温瓶 Blue Flask", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：图书馆遗失蓝色保温瓶 Blue Flask，请只通过平台联系。"));
        String found = create(author, notice("FOUND", "蓝色保温瓶 Blue Flask", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：图书馆捡到蓝色保温瓶 Blue Flask，请只通过平台联系。"));
        String keys = create(author, notice("FOUND", "蓝色保温瓶 Blue Flask", "KEYS", "LIBRARY", today, false,
                "虚构公告：图书馆捡到蓝色保温瓶 Blue Flask，类别不同。"));
        String sameType = create(author, notice("LOST", "蓝色保温瓶 Blue Flask", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：另一条丢失的蓝色保温瓶 Blue Flask。"));
        String closed = create(author, notice("FOUND", "蓝色保温瓶 Blue Flask", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：已归档的蓝色保温瓶 Blue Flask。"));
        jdbc.update("UPDATE notices SET status='CLOSED' WHERE id=?", closed);
        String review = create(author, notice("FOUND", "蓝色保温瓶 Blue Flask", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：认领复核中的蓝色保温瓶 Blue Flask。"));
        jdbc.update("UPDATE notices SET status='CLAIM_UNDER_REVIEW' WHERE id=?", review);
        String awaiting = create(author, notice("FOUND", "蓝色保温瓶 Blue Flask", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：待领取的蓝色保温瓶 Blue Flask。"));
        jdbc.update("UPDATE notices SET status='AWAITING_COLLECTION' WHERE id=?", awaiting);
        String returned = create(author, notice("FOUND", "蓝色保温瓶 Blue Flask", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：已归还的蓝色保温瓶 Blue Flask。"));
        jdbc.update("UPDATE notices SET status='RETURNED' WHERE id=?", returned);
        String hidden = create(author, notice("FOUND", "蓝色保温瓶 Blue Flask", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：隐藏的蓝色保温瓶 Blue Flask。"));
        jdbc.update("UPDATE notices SET hidden=TRUE WHERE id=?", hidden);
        String reserved = create(author, notice("FOUND", "蓝色保温瓶 Blue Flask", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：有效预留中的蓝色保温瓶 Blue Flask。"));
        jdbc.update("INSERT INTO active_reservations(notice_id,claim_id) VALUES(?,?)", reserved, UUID.randomUUID().toString());

        mvc.perform(get("/api/notices/" + lost + "/suggestions")).andExpect(status().isUnauthorized());
        long started = System.nanoTime();
        JsonNode page = suggestions(reader, lost);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(page.path("configVersion").asText()).isEqualTo("t12-2026-10-25");
        assertThat(page.path("disclaimer").asText()).contains("not prove ownership");
        assertThat(page.path("eligible").asBoolean()).isTrue();
        assertThat(ids(page)).containsExactly(found, keys);
        assertThat(ids(page)).doesNotContain(sameType, closed, review, awaiting, returned, hidden, reserved);
        assertThat(page.path("items").get(0).path("reasons").toString()).contains("Same category");
        assertThat(page.path("items").get(0).path("confidence").asInt())
                .isGreaterThan(page.path("items").get(1).path("confidence").asInt());

        JsonNode closedPage = suggestions(reader, closed);
        assertThat(closedPage.path("eligible").asBoolean()).isFalse();
        assertThat(closedPage.path("items")).isEmpty();
        mvc.perform(get("/api/notices/" + closed).session(reader.session())).andExpect(status().isOk());

        edit(author, found, notice("FOUND", "黑色雨伞一把", "UMBRELLA", "SPORTS_AREA", today, false,
                "虚构公告：运动场捡到黑色雨伞，公开描述已经改掉。"));
        long edited = System.nanoTime();
        JsonNode afterEdit = suggestions(reader, lost);
        assertThat(Duration.ofNanos(System.nanoTime() - edited)).isLessThan(Duration.ofSeconds(10));
        assertThat(ids(afterEdit).get(0)).isEqualTo(keys);

        jdbc.update("UPDATE notices SET status='OPEN' WHERE id=?", closed);
        long reopened = System.nanoTime();
        JsonNode afterOpen = suggestions(reader, lost);
        assertThat(Duration.ofNanos(System.nanoTime() - reopened)).isLessThan(Duration.ofSeconds(10));
        assertThat(ids(afterOpen).get(0)).isEqualTo(closed);
    }

    @Test
    void ignoreHidesThePairFromThatUserPermanently() throws Exception {
        BrowserSession author = registeredClient("ignore_author", "忽略发布同学");
        BrowserSession first = registeredClient("ignore_first", "忽略同学甲");
        BrowserSession second = registeredClient("ignore_second", "忽略同学乙");
        LocalDate today = LocalDate.now(MACAU);
        String lost = create(author, notice("LOST", "蓝色保温瓶", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：图书馆遗失蓝色保温瓶，请只通过平台联系。"));
        String found = create(author, notice("FOUND", "蓝色保温瓶", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：图书馆捡到蓝色保温瓶，请只通过平台联系。"));
        ignore(first, lost, found);
        ignore(first, lost, found);
        assertThat(ids(suggestions(first, lost))).doesNotContain(found);
        edit(author, found, notice("FOUND", "蓝色保温瓶新描述", "BOTTLE", "LIBRARY", today, false,
                "虚构公告：图书馆捡到蓝色保温瓶，描述已实质修改。"));
        assertThat(ids(suggestions(first, lost))).doesNotContain(found);
        assertThat(ids(suggestions(first, found))).doesNotContain(lost);
        assertThat(ids(suggestions(second, lost))).containsExactly(found);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ignored_pairs WHERE user_id=?", Integer.class, first.username())).isEqualTo(1);
    }

    @Test
    void tieUsesNoticeIdAndTheResponseStopsAtTwenty() throws Exception {
        BrowserSession author = registeredClient("cap_author", "上限发布同学");
        BrowserSession reader = registeredClient("cap_reader", "上限浏览同学");
        LocalDate today = LocalDate.now(MACAU);
        String lost = create(author, notice("LOST", "相同样本物品", "BAG", "CANTEEN", today, false,
                "虚构公告：食堂遗失相同样本，用来检查排序。"));
        List<String> found = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            found.add(create(author, notice("FOUND", "相同样本物品", "BAG", "CANTEEN", today, false,
                    "虚构公告：食堂捡到相同样本，用来检查排序。")));
        }
        found.sort(Comparator.naturalOrder());
        JsonNode page = suggestions(reader, lost);
        assertThat(page.path("totalEligible").asInt()).isEqualTo(21);
        assertThat(page.path("limited").asBoolean()).isTrue();
        assertThat(ids(page)).containsExactlyElementsOf(found.subList(0, 20));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM match_candidates WHERE config_version='t12-2026-10-25'", Integer.class)).isEqualTo(20);
    }

    private JsonNode suggestions(BrowserSession reader, String id) throws Exception {
        return read(mvc.perform(get("/api/notices/" + id + "/suggestions").session(reader.session())).andExpect(status().isOk()).andReturn());
    }

    private void ignore(BrowserSession reader, String sourceId, String otherId) throws Exception {
        mvc.perform(post("/api/notices/" + sourceId + "/suggestions/" + otherId + "/ignore").session(reader.session())
                        .header(reader.csrfHeader(), reader.csrfToken()))
                .andExpect(status().isNoContent());
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

    private List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("noticeId").asText()));
        return ids;
    }
}
