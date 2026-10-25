package edu.must.campuslostfound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * REQ-1.1 / AT-01 checks for registration, sign-in, sign-out, lockout and signed-in browsing.
 * The 30-minute inactivity limit is asserted from the session, not by waiting out the clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountApiIntegrationTest {
    private static final ZoneId MACAU = ZoneId.of("Asia/Macau");
    private static final String LOGIN_FAILED = "The credentials could not be verified.";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @Test
    void registrationStoresHashAndDoesNotStartASession() throws Exception {
        String password = "password-12345";
        MvcResult created = register("hash_user", "哈希同学", password);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = read(created);
        assertThat(body.path("username").asText()).isEqualTo("hash_user");
        assertThat(body.path("displayName").asText()).isEqualTo("哈希同学");
        assertThat(body.path("role").asText()).isEqualTo("STUDENT");
        assertThat(body.path("campusVerified").asBoolean()).isFalse();
        assertThat(created.getResponse().getContentAsString()).doesNotContain(password);
        assertThat(created.getRequest().getUserPrincipal()).isNull();
        MockHttpSession session = (MockHttpSession) created.getRequest().getSession(false);
        if (session != null) {
            mvc.perform(get("/api/session").session(session)).andExpect(jsonPath("$.authenticated").value(false));
        }

        String hash = jdbc.queryForObject("SELECT password_hash FROM accounts WHERE username_key=?", String.class, "hash_user");
        assertThat(hash).startsWith("$2a$10$");
        assertThat(hash).doesNotContain(password);
        assertThat(encoder.matches(password, hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT role FROM accounts WHERE username_key=?", String.class, "hash_user")).isEqualTo("STUDENT");
        assertThat(jdbc.queryForObject("SELECT campus_verified FROM accounts WHERE username_key=?", Boolean.class, "hash_user")).isFalse();
    }

    @Test
    void fieldBoundariesRejectPartialAccounts() throws Exception {
        long before = accounts();
        expectField("ab", "李雷", "password-12345", "username");
        expectField("a".repeat(33), "李雷", "password-12345", "username");
        expectField("用户名aa", "李雷", "password-12345", "username");
        expectField("bad name", "李雷", "password-12345", "username");
        expectField("bad-name", "李雷", "password-12345", "username");
        expectField("edge_user", "名", "password-12345", "displayName");
        expectField("edge_user", "名".repeat(41), "password-12345", "displayName");
        expectField("edge_user", "李\n雷", "password-12345", "displayName");
        expectField("edge_user", "边界同学", "123456789", "password");
        expectField("edge_user", "边界同学", "p".repeat(73), "password");
        expectField("edge_user", "边界同学", "密".repeat(30), "password");
        assertThat(accounts()).isEqualTo(before);

        register("edge_min", "李雷", "1234567890");
        register("edge_max", "名".repeat(40), "p".repeat(72));
        register("under_ok", "a_b", "password-12345");
        MvcResult trimmed = register("  spaced_name  ", "  李雷  ", "password-12345");
        assertThat(read(trimmed).path("username").asText()).isEqualTo("spaced_name");
        assertThat(read(trimmed).path("displayName").asText()).isEqualTo("李雷");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE username_key=?", Long.class, "a".repeat(32))).isZero();
        register("a".repeat(32), "三十二位", "password-12345");
    }

    @Test
    void duplicateUsernamesAreRejectedWithoutASecondRow() throws Exception {
        assertThat(register("CaseUser", "大小写", "password-12345").getResponse().getStatus()).isEqualTo(201);
        MvcResult duplicate = register("caseuser", "另一个名字", "other-password-123");
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(422);
        assertThat(read(duplicate).path("fieldErrors").path("username").asText()).contains("already in use");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE username_key=?", Long.class, "caseuser")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT display_name FROM accounts WHERE username_key=?", String.class, "caseuser")).isEqualTo("大小写");
        mvc.perform(formLogin("/api/login").user("caseuser").password("password-12345"))
                .andExpect(status().isOk())
                .andExpect(authenticated().withUsername("CaseUser"));
    }

    @Test
    void studentsCannotGrantARoleOrCampusIdentity() throws Exception {
        long before = accounts();
        long moderators = moderators();
        MvcResult role = mvc.perform(post("/api/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"role_user\",\"displayName\":\"权限同学\",\"password\":\"password-12345\",\"role\":\"MODERATOR\"}"))
                .andReturn();
        assertThat(role.getResponse().getStatus()).isEqualTo(422);
        assertThat(role.getResponse().getContentAsString()).doesNotContain("password-12345");
        MvcResult campus = mvc.perform(post("/api/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"campus_user\",\"displayName\":\"校园同学\",\"password\":\"password-12345\",\"studentId\":\"1240000000\",\"campusVerified\":true}"))
                .andReturn();
        assertThat(campus.getResponse().getStatus()).isEqualTo(422);
        assertThat(accounts()).isEqualTo(before);
        assertThat(moderators()).isEqualTo(moderators);

        register("plain_student", "普通同学", "password-12345");
        MvcResult login = mvc.perform(post("/api/login").with(csrf())
                        .param("username", "plain_student").param("password", "password-12345").param("role", "MODERATOR"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/api/session").session(session))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.campusVerified").value(false));
        mvc.perform(post("/api/account/role").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MODERATOR\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbc.queryForObject("SELECT role FROM accounts WHERE username_key=?", String.class, "plain_student")).isEqualTo("STUDENT");
        assertThat(moderators()).isEqualTo(moderators);
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET campus_verified=TRUE WHERE username_key='plain_student'"))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT campus_verified FROM accounts WHERE username_key=?", Boolean.class, "plain_student")).isFalse();
    }

    @Test
    void signInSignOutAndSessionCookieFollowTheAccountRules() throws Exception {
        String page = mvc.perform(get("/app/index.html")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("does not verify campus identity");
        assertThat(page).doesNotContain("campus-demo-2026");
        assertThat(page).doesNotContain("name=\"role\"");

        MvcResult login = mvc.perform(formLogin("/api/login").user("alice").password(TestAccountSeeder.PASSWORD))
                .andExpect(status().isOk())
                .andExpect(authenticated().withUsername("alice"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(30 * 60);
        assertThat(login.getResponse().getContentAsString()).doesNotContain(TestAccountSeeder.PASSWORD);
        mvc.perform(get("/api/session").session(session))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.displayName").value("Alice Demo"))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.campusVerified").value(false));
        mvc.perform(get("/api/session").with(user("alice")))
                .andExpect(jsonPath("$.displayName").value("Alice Demo"));

        mvc.perform(post("/api/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/notices").param("scope", "available")).andExpect(status().isUnauthorized());
    }

    @Test
    void failedSignInIsGenericAndLocksTheAccount() throws Exception {
        register("lock_user", "锁定同学", "password-lock-1");
        MvcResult unknown = login("nobody_user_zz", "wrong-password-zz");
        MvcResult wrong = login("lock_user", "wrong-password-zz");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getContentAsString()).isEqualTo(wrong.getResponse().getContentAsString());
        assertThat(unknown.getResponse().getContentAsString()).contains(LOGIN_FAILED);
        assertThat(unknown.getResponse().getContentAsString()).doesNotContain("nobody_user_zz").doesNotContain("lock_user");
        for (int i = 0; i < 4; i++) login("lock_user", "wrong-password-zz");
        assertThat(attempts("lock_user")).isEqualTo(5);
        assertThat(lockedUntil("lock_user")).isNotBlank();
        MvcResult locked = login("lock_user", "password-lock-1");
        assertThat(locked.getResponse().getStatus()).isEqualTo(401);
        assertThat(locked.getResponse().getContentAsString()).isEqualTo(wrong.getResponse().getContentAsString());

        jdbc.update("UPDATE accounts SET locked_until=? WHERE username_key=?", Instant.now().minusSeconds(5).toString(), "lock_user");
        mvc.perform(formLogin("/api/login").user("lock_user").password("password-lock-1"))
                .andExpect(status().isOk())
                .andExpect(authenticated().withUsername("lock_user"));
        assertThat(attempts("lock_user")).isZero();
        assertThat(lockedUntil("lock_user")).isNull();
        assertThat(auditText()).doesNotContain("wrong-password-zz").doesNotContain("password-lock-1");
    }

    @Test
    void oldFailuresLeaveTheWindowAndSuccessClearsThem() throws Exception {
        register("window_user", "窗口同学", "password-window-1");
        for (int i = 0; i < 4; i++) login("window_user", "wrong-password-zz");
        assertThat(attempts("window_user")).isEqualTo(4);
        assertThat(lockedUntil("window_user")).isNull();
        jdbc.update("UPDATE accounts SET window_started_at=? WHERE username_key=?",
                Instant.now().minus(Duration.ofMinutes(11)).toString(), "window_user");
        login("window_user", "wrong-password-zz");
        assertThat(attempts("window_user")).isEqualTo(1);
        assertThat(lockedUntil("window_user")).isNull();
        mvc.perform(formLogin("/api/login").user("window_user").password("password-window-1"))
                .andExpect(status().isOk());
        assertThat(attempts("window_user")).isZero();
    }

    @Test
    void signedInStudentCanBrowseAndAGuestCannot() throws Exception {
        cleanNotices();
        JsonNode open = createNotice();
        JsonNode closed = createNotice();
        mvc.perform(post("/api/notices/{id}/close", closed.path("id").asText()).with(user("alice")).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("version", 1, "reason", "重复的虚构公告，归档后不应出现在浏览列表"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/notices").param("scope", "available"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
        mvc.perform(get("/api/notices/{id}", open.path("id").asText())).andExpect(status().isUnauthorized());

        register("browse_user", "浏览同学", "browse-pass-1");
        MvcResult login = mvc.perform(formLogin("/api/login").user("browse_user").password("browse-pass-1"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        JsonNode page = read(mvc.perform(get("/api/notices").param("scope", "available").session(session))
                .andExpect(status().isOk()).andReturn());
        assertThat(ids(page)).contains(open.path("id").asText()).doesNotContain(closed.path("id").asText());
        String json = page.toString();
        assertThat(json).doesNotContain("password_hash").doesNotContain("studentId").doesNotContain("campusVerified");
        mvc.perform(get("/api/notices/{id}", open.path("id").asText()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(open.path("id").asText()));
    }

    private MvcResult register(String username, String displayName, String password) throws Exception {
        return mvc.perform(post("/api/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", username, "displayName", displayName, "password", password))))
                .andReturn();
    }

    private void expectField(String username, String displayName, String password, String field) throws Exception {
        MvcResult result = register(username, displayName, password);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(read(result).path("fieldErrors").path(field).asText()).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(password);
    }

    private MvcResult login(String username, String password) throws Exception {
        return mvc.perform(formLogin("/api/login").user(username).password(password)).andReturn();
    }

    private JsonNode createNotice() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "LOST");
        body.put("title", "图书馆蓝色保温瓶遗失");
        body.put("category", "BOTTLE");
        body.put("occurrenceDate", LocalDate.now(MACAU).minusDays(1).toString());
        body.put("approximate", false);
        body.put("area", "LIBRARY");
        body.put("description", "在图书馆附近遗失蓝色保温瓶，此为浏览测试用的虚构记录。");
        body.put("retainedPhotoIds", List.of());
        MockMultipartHttpServletRequestBuilder request = multipart("/api/notices");
        request.file(new MockMultipartFile("notice", "notice.json", "application/json", mapper.writeValueAsBytes(body)));
        request.header("Idempotency-Key", UUID.randomUUID().toString());
        return read(mvc.perform(request.with(user("alice")).with(csrf())).andExpect(status().isCreated()).andReturn());
    }

    private void cleanNotices() {
        for (String table : List.of("active_reservations", "claim_guards", "notice_photos", "notice_revisions",
                "notice_events", "notice_audit", "idempotency_records", "notices")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private JsonNode read(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
    private long accounts() { return jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Long.class); }
    private long moderators() { return jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE role='MODERATOR'", Long.class); }
    private int attempts(String username) { return jdbc.queryForObject("SELECT failed_attempts FROM accounts WHERE username_key=?", Integer.class, username); }
    private String lockedUntil(String username) { return jdbc.queryForObject("SELECT locked_until FROM accounts WHERE username_key=?", String.class, username); }
    private String auditText() {
        return jdbc.query("SELECT actor_id, action, target, outcome FROM account_audit",
                (rs, n) -> rs.getString(1) + rs.getString(2) + rs.getString(3) + rs.getString(4)).toString();
    }
    private List<String> ids(JsonNode page) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }
}
