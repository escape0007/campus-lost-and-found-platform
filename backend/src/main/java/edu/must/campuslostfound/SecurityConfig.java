package edu.must.campuslostfound;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
class SecurityConfig {
    @Bean UserDetailsService localDemoUsers(@Value("${server.address:127.0.0.1}") String address) throws Exception {
        // Known fictional credentials are only safe in a loopback-only development application.
        if (!InetAddress.getByName(address).isLoopbackAddress())
            throw new IllegalStateException("Demo authentication requires a loopback server.address. Integrate T09 before network deployment.");
        var encoder=new BCryptPasswordEncoder();
        String hash="{bcrypt}"+encoder.encode("campus-demo-2026");
        return new InMemoryUserDetailsManager(User.withUsername("alice").password(hash).roles("STUDENT").build(),
                User.withUsername("bob").password(hash).roles("STUDENT").build());
    }
    @Bean SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper) throws Exception {
        var csrfHandler=new CsrfTokenRequestAttributeHandler();
        http.csrf(c -> c.csrfTokenRequestHandler(csrfHandler))
            .authorizeHttpRequests(a -> a.requestMatchers("/", "/app/**", "/api/session", "/api/login", "/error").permitAll().anyRequest().authenticated())
            .requestCache(c -> c.disable())
            .formLogin(f -> f.loginProcessingUrl("/api/login")
                .successHandler((req,res,auth)-> {res.setContentType("application/json");mapper.writeValue(res.getOutputStream(),Map.of("username",auth.getName()));})
                .failureHandler((req,res,e)-> {res.setStatus(401);res.setContentType("application/json");mapper.writeValue(res.getOutputStream(),Map.of("code","LOGIN_FAILED","message","The credentials could not be verified.","fieldErrors",Map.of()));}))
            .logout(l -> l.logoutUrl("/api/logout").invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
                .logoutSuccessHandler((req,res,auth)->res.setStatus(204)))
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex)-> {
                res.setStatus(401);res.setContentType("application/json");mapper.writeValue(res.getOutputStream(),Map.of("code","SIGN_IN_REQUIRED","message","Please sign in to continue.","fieldErrors",Map.of()));
            }).accessDeniedHandler((req,res,ex)-> {
                res.setStatus(403);res.setContentType("application/json");mapper.writeValue(res.getOutputStream(),Map.of("code","FORBIDDEN","message","This request is not permitted. Refresh your session and try again.","fieldErrors",Map.of()));
            }))
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"))
                .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)));
        return http.build();
    }
}
