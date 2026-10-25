package edu.must.campuslostfound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T10 cross-module regressions using registered accounts and actual login sessions.
 * No with(user), synthetic csrf(), registration shortcut, search implementation,
 * elapsed 30-minute timeout, or six-volunteer usability result is claimed here.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:t10-flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class T10FlowIntegrationTest {
    private static final String PASSWORD = "t10-fictional-password-2026";
    private static final ZoneId MACAU = ZoneId.of("Asia/Macau");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    record BrowserSession(MockHttpSession session, String csrfHeader, String csrfToken, String username) {}

    @BeforeEach
    void cleanFlowFixtures() {
        for (String table : List.of("active_reservations", "claim_guards", "notice_photos", "notice_revisions",
                "notice_events", "notice_audit", "idempotency_records", "notices")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void twoRegisteredAccountsCompletePublicationBrowsingEditArchiveAndLogoutFlow() throws Exception {
        BrowserSession author = registeredClient("author", "发布流程同学");
        BrowserSession reader = registeredClient("reader", "浏览流程同学");
        Map<String, Object> original = noticeBody();
        JsonNode published = create(author, original);
        String id = published.path("id").asText();
        assertThat(published.path("history").get(0).path("actor").asText()).isEqualTo("发布流程同学");

        JsonNode otherView = read(mvc.perform(get("/api/notices/{id}", id).session(reader.session()))
                .andExpect(status().isOk()).andReturn());
        for (String field : List.of("type", "title", "category", "occurrenceDate", "area", "description")) {
            assertThat(otherView.path(field).asText()).isEqualTo(original.get(field).toString());
        }
        assertThat(otherView.path("approximate").asBoolean()).isTrue();
        assertThat(otherView.path("canEdit").asBoolean()).isFalse();
        assertThat(otherView.path("canClose").asBoolean()).isFalse();
        assertThat(ids(read(mvc.perform(get("/api/notices").session(reader.session()))
                .andExpect(status().isOk()).andReturn()))).containsExactly(id);

        Map<String, Object> edit = noticeBody();
        edit.put("version", 1);
        edit.put("title", "修改后的图书馆蓝色保温水瓶公告");
        mvc.perform(write(id, edit, reader)).andExpect(status().isForbidden());
        mvc.perform(post("/api/notices/{id}/close", id).session(reader.session())
                        .header(reader.csrfHeader(), reader.csrfToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("version", 1, "reason", "别人不能归档这条虚构公告"))))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT version FROM notices WHERE id=?", Long.class, id)).isEqualTo(1);

        JsonNode edited = read(mvc.perform(write(id, edit, author)).andExpect(status().isOk()).andReturn());
        assertThat(edited.path("id").asText()).isEqualTo(id);
        assertThat(edited.path("version").asLong()).isEqualTo(2);
        mvc.perform(get("/api/notices/{id}", id).session(reader.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value(edit.get("title")))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(post("/api/notices/{id}/close", id).session(author.session())
                        .header(author.csrfHeader(), author.csrfToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("version", 2, "reason", "虚构流程记录已处理，主动归档"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.version").value(3));
        assertThat(ids(read(mvc.perform(get("/api/notices").session(reader.session()))
                .andExpect(status().isOk()).andReturn()))).isEmpty();
        mvc.perform(get("/api/notices/{id}", id).session(reader.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.canClose").value(false));

        mvc.perform(post("/api/logout").session(author.session()).header(author.csrfHeader(), author.csrfToken()))
                .andExpect(status().isNoContent());
        assertThat(author.session().isInvalid()).isTrue();
        assertOldSessionCannotReadOrWrite(author, id);
        assertThat(jdbc.queryForObject("SELECT version FROM notices WHERE id=?", Long.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notice_revisions WHERE notice_id=?", Integer.class, id)).isEqualTo(3);
        mvc.perform(get("/api/session").session(reader.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true));
    }

    @Test
    void successfulLoginRotatesAnonymousTokenAndRequiresFreshTokenForPublication() throws Exception {
        String username = uniqueUsername("rotate");
        BrowserSession anonymous = anonymousSession();
        register(anonymous, username, "令牌更新同学");
        String anonymousSessionId = anonymous.session().getId();
        BrowserSession signedIn = login(anonymous, username);
        assertThat(signedIn.session().getId()).isNotEqualTo(anonymousSessionId);
        assertThat(signedIn.csrfToken()).isNotEqualTo(anonymous.csrfToken());

        BrowserSession staleToken = new BrowserSession(signedIn.session(), anonymous.csrfHeader(), anonymous.csrfToken(), username);
        mvc.perform(write(null, noticeBody(), staleToken)).andExpect(status().isForbidden());
        assertThat(noticeCount()).isZero();
        create(signedIn, noticeBody());
        assertThat(noticeCount()).isEqualTo(1);
    }

    @Test
    void aTokenFromAnotherLoggedInBrowserCannotAuthorizePublicationOrLogout() throws Exception {
        BrowserSession first = registeredClient("csrf_one", "会话甲同学");
        BrowserSession second = registeredClient("csrf_two", "会话乙同学");
        BrowserSession copiedToken = new BrowserSession(first.session(), second.csrfHeader(), second.csrfToken(), first.username());
        mvc.perform(write(null, noticeBody(), copiedToken)).andExpect(status().isForbidden());
        mvc.perform(post("/api/logout").session(first.session()).header(second.csrfHeader(), second.csrfToken()))
                .andExpect(status().isForbidden());
        assertThat(first.session().isInvalid()).isFalse();
        assertThat(noticeCount()).isZero();
        create(first, noticeBody());
        mvc.perform(get("/api/session").session(second.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(second.username()));
    }

    @Test
    void explicitlyInvalidatedSessionLosesReadAndWriteAccessAndReloginUsesFreshState() throws Exception {
        BrowserSession client = registeredClient("invalidate", "会话失效同学");
        String id = create(client, noticeBody()).path("id").asText();
        client.session().invalidate();
        assertOldSessionCannotReadOrWrite(client, id);
        assertThat(jdbc.queryForObject("SELECT version FROM notices WHERE id=?", Long.class, id)).isEqualTo(1);

        BrowserSession relogged = login(anonymousSession(), client.username());
        assertThat(relogged.csrfToken()).isNotEqualTo(client.csrfToken());
        mvc.perform(get("/api/notices/{id}", id).session(relogged.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canEdit").value(true));
        Map<String, Object> changed = noticeBody();
        changed.put("version", 1);
        changed.put("description", "这是重新登录后保存的虚构公告描述，用于确认新会话可以正常修改数据。");
        mvc.perform(write(id, changed, relogged)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void defaultBrowsePaginatesOnlyAvailableNoticesWithoutDuplicatesOrHiddenRows() throws Exception {
        BrowserSession author = registeredClient("list_author", "列表发布同学");
        BrowserSession reader = registeredClient("list_reader", "列表浏览同学");
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 22; i++) expected.add(create(author, noticeBody()).path("id").asText());
        jdbc.update("UPDATE notices SET status='CLAIM_UNDER_REVIEW' WHERE id=?", expected.get(0));
        for (String excludedState : List.of("AWAITING_COLLECTION", "RETURNED", "CLOSED")) {
            String id = create(author, noticeBody()).path("id").asText();
            jdbc.update("UPDATE notices SET status=? WHERE id=?", excludedState, id);
        }
        String hidden = create(author, noticeBody()).path("id").asText();
        jdbc.update("UPDATE notices SET hidden=TRUE WHERE id=?", hidden);
        String reserved = create(author, noticeBody()).path("id").asText();
        jdbc.update("INSERT INTO active_reservations(notice_id,claim_id) VALUES(?,?)", reserved, UUID.randomUUID().toString());
        // A tied creation time makes stable pagination necessary rather than accidental.
        jdbc.update("UPDATE notices SET created_at='2026-10-25T00:00:00Z'");

        JsonNode first = read(mvc.perform(get("/api/notices").session(reader.session()))
                .andExpect(status().isOk()).andReturn());
        JsonNode firstAgain = read(mvc.perform(get("/api/notices").session(reader.session()))
                .andExpect(status().isOk()).andReturn());
        JsonNode second = read(mvc.perform(get("/api/notices").param("page", "1").session(reader.session()))
                .andExpect(status().isOk()).andReturn());
        assertThat(first.path("totalElements").asLong()).isEqualTo(22);
        assertThat(first.path("items").size()).isEqualTo(20);
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        assertThat(ids(firstAgain)).isEqualTo(ids(first));
        assertThat(second.path("items").size()).isEqualTo(2);
        assertThat(second.path("hasNext").asBoolean()).isFalse();
        List<String> combined = new ArrayList<>(ids(first));
        combined.addAll(ids(second));
        assertThat(new HashSet<>(combined)).hasSize(22);
        assertThat(combined).containsExactlyInAnyOrderElementsOf(expected);

        JsonNode emptyParameters = read(mvc.perform(get("/api/notices").param("scope", "").param("page", "").session(reader.session()))
                .andExpect(status().isOk()).andReturn());
        assertThat(ids(emptyParameters)).isEqualTo(ids(first));
        assertThat(emptyParameters.path("page").asInt()).isZero();
        mvc.perform(get("/api/notices").param("page", "100000").session(reader.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void malformedListParametersReturnSafeValidationErrorsInsteadOfServerFailures() throws Exception {
        BrowserSession reader = registeredClient("invalid_query", "参数校验同学");
        for (String scope : List.of("unknown", "null", "AVAILABLE")) {
            mvc.perform(get("/api/notices").param("scope", scope).session(reader.session()))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION"))
                    .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
        }
        for (String page : List.of("-1", "100001", "null", "abc", "2147483648")) {
            MvcResult result = mvc.perform(get("/api/notices").param("page", page).session(reader.session()))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION"))
                    .andExpect(jsonPath("$.fieldErrors").isNotEmpty()).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("SELECT", "SQLException", "stackTrace");
        }
        assertThat(noticeCount()).isZero();
    }

    @Test
    void repeatedUnchangedSavesWithFreshKeysDoNotProducePhantomChanges() throws Exception {
        BrowserSession author = registeredClient("no_change", "无变化保存同学");
        JsonNode original = create(author, noticeBody());
        String id = original.path("id").asText();
        Map<String, Object> unchanged = noticeBody();
        unchanged.put("version", 1);
        for (int i = 0; i < 3; i++) {
            JsonNode saved = read(mvc.perform(write(id, unchanged, author)).andExpect(status().isOk()).andReturn());
            assertThat(saved.path("version").asLong()).isEqualTo(1);
            assertThat(saved.path("materialVersion").asLong()).isEqualTo(1);
            assertThat(saved.path("updatedAt").asText()).isEqualTo(original.path("updatedAt").asText());
            assertThat(saved.path("history").size()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notice_events WHERE notice_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notice_audit WHERE notice_id=?", Integer.class, id)).isEqualTo(1);
    }

    private BrowserSession registeredClient(String prefix, String displayName) throws Exception {
        BrowserSession anonymous = anonymousSession();
        String username = uniqueUsername(prefix);
        register(anonymous, username, displayName);
        return login(anonymous, username);
    }

    private BrowserSession anonymousSession() throws Exception {
        MvcResult result = mvc.perform(get("/api/session")).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false)).andReturn();
        JsonNode body = read(result);
        return new BrowserSession((MockHttpSession) result.getRequest().getSession(false),
                body.path("csrfHeaderName").asText(), body.path("csrfToken").asText(), "");
    }

    private void register(BrowserSession anonymous, String username, String displayName) throws Exception {
        mvc.perform(post("/api/register").session(anonymous.session())
                        .header(anonymous.csrfHeader(), anonymous.csrfToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", username, "displayName", displayName, "password", PASSWORD))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.username").value(username));
    }

    private BrowserSession login(BrowserSession anonymous, String username) throws Exception {
        MvcResult login = mvc.perform(post("/api/login").session(anonymous.session())
                        .header(anonymous.csrfHeader(), anonymous.csrfToken())
                        .param("username", username).param("password", PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(username)).andReturn();
        MockHttpSession authenticated = (MockHttpSession) login.getRequest().getSession(false);
        JsonNode fresh = read(mvc.perform(get("/api/session").session(authenticated))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value(username)).andReturn());
        return new BrowserSession(authenticated, fresh.path("csrfHeaderName").asText(), fresh.path("csrfToken").asText(), username);
    }

    private JsonNode create(BrowserSession client, Map<String, Object> body) throws Exception {
        return read(mvc.perform(write(null, body, client)).andExpect(status().isCreated()).andReturn());
    }

    private MockMultipartHttpServletRequestBuilder write(String id, Map<String, Object> body, BrowserSession client) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(id == null ? "/api/notices" : "/api/notices/" + id);
        request.file(new MockMultipartFile("notice", "notice.json", "application/json", mapper.writeValueAsBytes(body)));
        if (id != null) request.with(r -> { r.setMethod("PUT"); return r; });
        request.session(client.session());
        request.header(client.csrfHeader(), client.csrfToken());
        request.header("Idempotency-Key", UUID.randomUUID().toString());
        return request;
    }

    private void assertOldSessionCannotReadOrWrite(BrowserSession stale, String id) throws Exception {
        mvc.perform(get("/api/notices/{id}", id).session(stale.session())).andExpect(status().isUnauthorized());
        Map<String, Object> changed = noticeBody();
        changed.put("version", 1);
        mvc.perform(write(id, changed, stale)).andExpect(status().isForbidden());
        assertThat(stale.session().isInvalid()).isTrue();
    }

    private Map<String, Object> noticeBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "LOST");
        body.put("title", "图书馆附近遗失蓝色保温水瓶");
        body.put("category", "BOTTLE");
        body.put("occurrenceDate", LocalDate.now(MACAU).minusDays(1).toString());
        body.put("approximate", true);
        body.put("area", "LIBRARY");
        body.put("description", "这是端到端回归使用的虚构公告，在图书馆附近遗失蓝色保温瓶，请通过平台联系。");
        body.put("retainedPhotoIds", List.of());
        return body;
    }

    private String uniqueUsername(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8); }
    private int noticeCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM notices", Integer.class); }
    private JsonNode read(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
    private List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }
}
