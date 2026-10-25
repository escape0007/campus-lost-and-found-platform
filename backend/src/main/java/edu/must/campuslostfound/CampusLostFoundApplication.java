package edu.must.campuslostfound;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class CampusLostFoundApplication {
    public static void main(String[] args) { SpringApplication.run(CampusLostFoundApplication.class, args); }
    @Bean Clock campusClock() { return Clock.system(ZoneId.of("Asia/Macau")); }
}
