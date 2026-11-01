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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T11 manual search and combined filters. REQ-1.4, AT-04 browsing rules and CAP-03 page limits.
 * This does not record the six-volunteer timing protocol.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:t11-filter;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class T11FilterIntegrationTest {
    private static final String PASSWORD = "t11-fictional-password-2026";
    private static final ZoneId MACAU = ZoneId.of("Asia/Macau");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    record BrowserSession(MockHttpSession session, String csrfHeader, String csrfToken, String username) {}

    @BeforeEach
    void cleanNotices() {
        for (String table : List.of("alert_event_deliveries", "match_alerts", "ignored_pairs", "match_candidates", "active_reservations", "claim_guards", "notice_photos", "notice_revisions",
                "notice_events", "notice_audit", "idempotency_records", "notices")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void combinedFiltersKeepPublicNoticesAndRejectInvalidValues() throws Exception {
        BrowserSession author = registeredClient("filter_author", "筛选发布同学");
        BrowserSession reader = registeredClient("filter_reader", "筛选浏览同学");
        LocalDate today = LocalDate.now(MACAU);
        String blue = create(author, notice("LOST", "蓝色保温瓶 Blue Flask", "BOTTLE", "LIBRARY", today.minusDays(2),
                "虚构公告：图书馆遗失蓝色保温瓶 Blue Flask，请只通过平台联系。", true));
        String review = create(author, notice("LOST", "蓝色保温瓶待确认", "BOTTLE", "LIBRARY", today.minusDays(2),
                "虚构公告：同一图书馆的蓝色保温瓶正在认领复核。", false));
        jdbc.update("UPDATE notices SET status='CLAIM_UNDER_REVIEW' WHERE id=?", review);
        String found = create(author, notice("FOUND", "蓝色保温瓶已捡到", "BOTTLE", "LIBRARY", today.minusDays(2),
                "虚构公告：图书馆捡到一只蓝色保温瓶，等待失主认领。", false));
        String canteen = create(author, notice("LOST", "食堂蓝色保温瓶", "BOTTLE", "CANTEEN", today.minusDays(2),
                "虚构公告：食堂遗失蓝色保温瓶，地点与图书馆不同。", false));
        String keys = create(author, notice("LOST", "图书馆蓝色钥匙", "KEYS", "LIBRARY", today.minusDays(2),
                "虚构公告：图书馆遗失一串蓝色钥匙，不是水瓶。", false));
        String older = create(author, notice("LOST", "更早的蓝色保温瓶", "BOTTLE", "LIBRARY", today.minusDays(9),
                "虚构公告：九天前在图书馆遗失的蓝色保温瓶。", false));
        String literal = create(author, notice("FOUND", "Lost %_token bottle", "OTHER", "OTHER", today.minusDays(1),
                "虚构公告：标题里包含百分号和下划线，用来确认它们按字面匹配。", false));
        for (String status : List.of("AWAITING_COLLECTION", "RETURNED", "CLOSED")) {
            String id = create(author, notice("LOST", "蓝色保温瓶 " + status, "BOTTLE", "LIBRARY", today.minusDays(2),
                    "虚构公告：这条蓝色保温瓶不应出现在可用筛选结果中。", false));
            jdbc.update("UPDATE notices SET status=? WHERE id=?", status, id);
        }
        String hidden = create(author, notice("LOST", "隐藏的蓝色保温瓶", "BOTTLE", "LIBRARY", today.minusDays(2),
                "虚构公告：隐藏后不能通过关键词再被浏览到。", false));
        jdbc.update("UPDATE notices SET hidden=TRUE WHERE id=?", hidden);
        String reserved = create(author, notice("LOST", "预留的蓝色保温瓶", "BOTTLE", "LIBRARY", today.minusDays(2),
                "虚构公告：有效预留期间退出可用搜索结果。", false));
        jdbc.update("INSERT INTO active_reservations(notice_id,claim_id) VALUES(?,?)", reserved, UUID.randomUUID().toString());

        JsonNode openBoard = search(reader);
        assertThat(openBoard.path("size").asInt()).isEqualTo(20);
        assertThat(ids(openBoard)).contains(blue, review, found, canteen, keys, older, literal)
                .doesNotContain(hidden, reserved);
        assertThat(openBoard.toString()).doesNotContain("AWAITING_COLLECTION", "RETURNED", "CLOSED", "password", "evidence");

        assertThat(ids(search(reader, "keyword", "BLUE FLASK"))).containsExactly(blue);
        assertThat(ids(search(reader, "keyword", "蓝色　保温瓶"))).contains(blue, review, found, canteen, older)
                .doesNotContain(keys, literal, hidden, reserved);
        assertThat(ids(search(reader, "keyword", "%"))).containsExactly(literal);
        assertThat(ids(search(reader, "keyword", "%_token"))).containsExactly(literal);
        assertThat(ids(search(reader, "keyword", "%' OR '1'='1"))).isEmpty();

        JsonNode combined = search(reader, Map.of(
                "keyword", "蓝色", "type", "LOST", "category", "BOTTLE", "area", "LIBRARY",
                "from", today.minusDays(3).toString(), "to", today.minusDays(1).toString()));
        assertThat(combined.path("totalElements").asLong()).isEqualTo(2);
        assertThat(ids(combined)).containsExactlyInAnyOrder(blue, review);
        assertThat(combined.path("items").findValuesAsText("approximate")).contains("true");
        assertThat(ids(search(reader, Map.of("keyword", "蓝色", "type", "LOST", "category", "BOTTLE",
                "area", "LIBRARY", "from", today.minusDays(3).toString(), "to", today.minusDays(1).toString(),
                "openOnly", "true")))).containsExactly(blue);

        JsonNode empty = search(reader, "keyword", "不存在的紫色滑板zzz");
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("totalElements").asLong()).isZero();

        JsonNode mine = read(mvc.perform(get("/api/notices").param("scope", "mine").session(author.session()))
                .andExpect(status().isOk()).andReturn());
        assertThat(ids(mine).size()).isGreaterThan(ids(openBoard).size());

        mvc.perform(get("/api/notices").param("from", today.toString()).param("to", today.minusDays(1).toString()).session(reader.session()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors.to").isNotEmpty());
        for (String[] bad : new String[][] {{"type", "lost"}, {"category", "PHONE"}, {"area", "GATE"},
                {"from", "2026-13-40"}, {"openOnly", "yes"}, {"keyword", "蓝".repeat(81)}}) {
            MvcResult result = mvc.perform(get("/api/notices").param(bad[0], bad[1]).session(reader.session()))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION"))
                    .andExpect(jsonPath("$.fieldErrors." + bad[0]).isNotEmpty()).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("SELECT", "SQLException");
        }
        mvc.perform(get("/api/notices").param("keyword", "蓝色")).andExpect(status().isUnauthorized());

        jdbc.update("UPDATE notices SET status='CLOSED' WHERE id=?", blue);
        assertThat(ids(search(reader, "keyword", "BLUE FLASK"))).isEmpty();
        mvc.perform(get("/api/notices/{id}", blue).session(reader.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void pagingKeepsFiltersAndRereadsCurrentStatus() throws Exception {
        BrowserSession author = registeredClient("page_author", "分页发布同学");
        BrowserSession reader = registeredClient("page_reader", "分页浏览同学");
        LocalDate day = LocalDate.now(MACAU).minusDays(1);
        List<String> matches = new ArrayList<>();
        for (int i = 0; i < 22; i++) {
            String id = create(author, notice("FOUND", "分页样本 page-sample " + i, "OTHER", "SPORTS_AREA", day,
                    "虚构公告：用于确认翻页仍按 page-sample 筛选并重查状态。", false));
            jdbc.update("UPDATE notices SET created_at=? WHERE id=?", String.format("2026-10-20T%02d:00:00Z", i), id);
            matches.add(id);
        }
        String other = create(author, notice("LOST", "普通雨伞不参与分页", "UMBRELLA", "DORMITORY", day,
                "虚构公告：这条雨伞没有样本字样，不能混进筛选页。", false));
        jdbc.update("UPDATE notices SET created_at='2026-10-21T00:00:00Z' WHERE id=?", other);

        JsonNode first = search(reader, Map.of("keyword", "page-sample", "page", "0"));
        JsonNode second = search(reader, Map.of("keyword", "page-sample", "page", "1"));
        assertThat(first.path("totalElements").asLong()).isEqualTo(22);
        assertThat(first.path("size").asInt()).isEqualTo(20);
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        assertThat(ids(first)).hasSize(20).doesNotContain(other);
        assertThat(ids(second)).containsExactly(matches.get(1), matches.get(0));
        assertThat(second.path("hasNext").asBoolean()).isFalse();

        jdbc.update("UPDATE notices SET status='RETURNED' WHERE id=?", matches.get(21));
        JsonNode refreshed = search(reader, Map.of("keyword", "page-sample", "page", "0", "size", "20"));
        assertThat(ids(refreshed)).doesNotContain(matches.get(21));
        assertThat(refreshed.path("totalElements").asLong()).isEqualTo(21);
        assertThat(ids(search(reader, Map.of("keyword", "page-sample", "page", "1")))).doesNotContain(matches.get(21), other);
    }

    @Test
    void pageSizeDefaultsToTwentyAndNeverExceedsFifty() throws Exception {
        BrowserSession author = registeredClient("size_author", "页长发布同学");
        BrowserSession reader = registeredClient("size_reader", "页长浏览同学");
        LocalDate day = LocalDate.now(MACAU);
        for (int i = 0; i < 51; i++) {
            create(author, notice("LOST", "页长样本 " + i, "BAG", "TEACHING_BUILDING", day,
                    "虚构公告：用来确认默认 20 条、最大 50 条的页长。", false));
        }
        JsonNode defaults = search(reader, "keyword", "页长样本");
        assertThat(defaults.path("size").asInt()).isEqualTo(20);
        assertThat(defaults.path("items")).hasSize(20);
        assertThat(defaults.path("hasNext").asBoolean()).isTrue();
        assertThat(defaults.path("totalElements").asLong()).isEqualTo(51);

        JsonNode capped = search(reader, Map.of("keyword", "页长样本", "size", "50"));
        assertThat(capped.path("size").asInt()).isEqualTo(50);
        assertThat(capped.path("items")).hasSize(50);
        assertThat(capped.path("hasNext").asBoolean()).isTrue();
        JsonNode remainder = search(reader, Map.of("keyword", "页长样本", "size", "50", "page", "1"));
        assertThat(remainder.path("items")).hasSize(1);
        assertThat(ids(capped)).doesNotContainAnyElementsOf(ids(remainder));

        mvc.perform(get("/api/notices").param("size", "51").session(reader.session()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors.size").isNotEmpty());
        mvc.perform(get("/api/notices").param("size", "0").session(reader.session()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors.size").isNotEmpty());
        mvc.perform(get("/api/notices").param("size", "abc").session(reader.session()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    private JsonNode search(BrowserSession reader, String name, String value) throws Exception {
        return search(reader, Map.of(name, value));
    }

    private JsonNode search(BrowserSession reader) throws Exception {
        return search(reader, Map.of());
    }

    private JsonNode search(BrowserSession reader, Map<String, String> filters) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/notices").param("scope", "available").session(reader.session());
        filters.forEach(request::param);
        return read(mvc.perform(request).andExpect(status().isOk()).andReturn());
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

    private Map<String, Object> notice(String type, String title, String category, String area, LocalDate date, String description, boolean approximate) {
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
        page.path("items").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }
}
