package edu.must.campuslostfound;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
class SecurityConfig {
    private static final String LOGIN_FAILED = "The credentials could not be verified.";

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(AccountService.BCRYPT_STRENGTH); }

    @Bean SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper, AccountService accounts) throws Exception {
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        http.csrf(c -> c.csrfTokenRequestHandler(csrfHandler))
            .authenticationProvider(new AccountAuthenticationProvider(accounts))
            .authorizeHttpRequests(a -> a.requestMatchers("/", "/app/**", "/api/session", "/api/login", "/api/register", "/error").permitAll().anyRequest().authenticated())
            .requestCache(c -> c.disable())
            .formLogin(f -> f.loginProcessingUrl("/api/login")
                .successHandler((req, res, auth) -> {
                    req.getSession().setMaxInactiveInterval(30 * 60);
                    var profile = accounts.profile(auth.getName()).orElseThrow();
                    res.setContentType("application/json");
                    mapper.writeValue(res.getOutputStream(), Map.of(
                            "username", profile.username(),
                            "displayName", profile.displayName(),
                            "role", profile.role(),
                            "campusVerified", false));
                })
                .failureHandler((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    mapper.writeValue(res.getOutputStream(), Map.of("code", "LOGIN_FAILED", "message", LOGIN_FAILED, "fieldErrors", Map.of()));
                }))
            .logout(l -> l.logoutUrl("/api/logout").invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
                .logoutSuccessHandler((req, res, auth) -> {
                    if (auth != null) accounts.recordLogout(auth.getName());
                    res.setStatus(204);
                }))
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(401);
                res.setContentType("application/json");
                mapper.writeValue(res.getOutputStream(), Map.of("code", "SIGN_IN_REQUIRED", "message", "Please sign in to continue.", "fieldErrors", Map.of()));
            }).accessDeniedHandler((req, res, ex) -> {
                res.setStatus(403);
                res.setContentType("application/json");
                mapper.writeValue(res.getOutputStream(), Map.of("code", "FORBIDDEN", "message", "This request is not permitted. Refresh your session and try again.", "fieldErrors", Map.of()));
            }))
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"))
                .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)));
        return http.build();
    }
}
