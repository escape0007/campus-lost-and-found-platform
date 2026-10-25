package edu.must.campuslostfound;

import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Fictional accounts used only by the automated tests. The running application starts with no accounts. */
@Configuration
@Profile("test")
class TestAccountSeeder {
    static final String PASSWORD = "campus-demo-2026";

    @Bean ApplicationRunner seedDemoAccounts(JdbcTemplate db, PasswordEncoder encoder, java.time.Clock clock) {
        return args -> {
            seed(db, encoder, clock, "alice", "Alice Demo");
            seed(db, encoder, clock, "bob", "Bob Demo");
        };
    }

    private void seed(JdbcTemplate db, PasswordEncoder encoder, java.time.Clock clock, String username, String displayName) {
        Long existing = db.queryForObject("SELECT COUNT(*) FROM accounts WHERE username_key=?", Long.class, username);
        if (existing != null && existing > 0) return;
        db.update("INSERT INTO accounts(id,username,username_key,display_name,password_hash,role,status,campus_verified,failed_attempts,created_at) VALUES(?,?,?,?,?,'STUDENT','ACTIVE',FALSE,0,?)",
                UUID.randomUUID().toString(), username, username, displayName, encoder.encode(PASSWORD), clock.instant().toString());
    }
}
