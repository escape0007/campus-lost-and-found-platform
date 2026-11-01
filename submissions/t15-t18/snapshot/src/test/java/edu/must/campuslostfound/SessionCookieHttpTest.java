package edu.must.campuslostfound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the real servlet session cookie. MockMvc does not emit the container Set-Cookie header. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionCookieHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    @Test
    void loginCookieIsHttpOnlyAndLogoutEndsBrowsing() throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        URI base = URI.create("http://127.0.0.1:" + port);
        HttpResponse<String> session = client.send(HttpRequest.newBuilder(base.resolve("/api/session")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(session.statusCode()).isEqualTo(200);
        String setCookie = String.join(";", session.headers().allValues("set-cookie"));
        assertThat(setCookie.toLowerCase()).contains("jsessionid").contains("httponly").contains("samesite=strict");

        JsonNode body = mapper.readTree(session.body());
        String form = "username=" + URLEncoder.encode("alice", StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(TestAccountSeeder.PASSWORD, StandardCharsets.UTF_8);
        HttpResponse<String> login = client.send(HttpRequest.newBuilder(base.resolve("/api/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header(body.path("csrfHeaderName").asText(), body.path("csrfToken").asText())
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).doesNotContain(TestAccountSeeder.PASSWORD);
        assertThat(mapper.readTree(login.body()).path("campusVerified").asBoolean()).isFalse();

        HttpResponse<String> notices = client.send(HttpRequest.newBuilder(base.resolve("/api/notices?scope=available")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(notices.statusCode()).isEqualTo(200);

        HttpResponse<String> fresh = client.send(HttpRequest.newBuilder(base.resolve("/api/session")).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonNode csrf = mapper.readTree(fresh.body());
        HttpResponse<String> logout = client.send(HttpRequest.newBuilder(base.resolve("/api/logout"))
                .header(csrf.path("csrfHeaderName").asText(), csrf.path("csrfToken").asText())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).isEqualTo(204);
        List<String> cleared = logout.headers().allValues("set-cookie");
        assertThat(String.join(";", cleared).toLowerCase()).contains("jsessionid");

        HttpResponse<String> after = client.send(HttpRequest.newBuilder(base.resolve("/api/notices?scope=available")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(after.statusCode()).isEqualTo(401);
    }
}
