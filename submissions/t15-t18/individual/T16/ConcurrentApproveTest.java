package edu.must.campuslostfound;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T16: 20 concurrent approve requests on the same claim — exactly one succeeds.
 * The FOR UPDATE lock on notices serializes approvals; all but the first must fail.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentApproveTest {

    @Autowired ClaimService claims;
    @Autowired NoticeService notices;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder encoder;

    @Test
    void twentyConcurrentApprovesExactlyOneWins() throws Exception {
        String finder = "conc_finder"; String loser = "conc_loser";
        seedUser(finder); seedUser(loser);
        var finderActor = accounts.actor(finder);
        var loserActor = accounts.actor(loser);

        // finder posts FOUND
        var f = notices.create(new NoticeModels.Write("FOUND","Found keys near lib","KEYS","2026-10-29",true,"LIBRARY","Lost keys set with blue strap, around 3 keys total.",1L,List.of()),List.of(),"key-"+UUID.randomUUID(),finderActor);
        // loser submits claim
        var c = claims.submit(new ClaimModels.SubmitClaim(null,"Blue strap with a small penguin charm, exactly 3 keys."),f.id(),loserActor);

        // fire 20 concurrent approves
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i=0;i<20;i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                try { ready.await(); } catch(Exception e){}
                try { claims.approve(c.id(), new ClaimModels.Decision("ok"), finderActor); return true; }
                catch(Exception e) { return false; }
            }));
        }
        pool.shutdown();
        long wins = results.stream().filter(future -> { try { return future.get(10,TimeUnit.SECONDS); } catch(Exception e){ return false; } }).count();
        assertEquals(1, wins, "Exactly one of 20 concurrent approves must succeed");
    }

    private void seedUser(String u) {
        Long n = db.queryForObject("SELECT COUNT(*) FROM accounts WHERE username=?", Long.class, u);
        if (n != null && n > 0) return;
        db.update("INSERT INTO accounts(id,username,username_key,display_name,password_hash,role,status,campus_verified,failed_attempts,created_at) VALUES(?,?,?,?,?,'STUDENT','ACTIVE',FALSE,0,?)",
                UUID.randomUUID().toString(), u, u, u, encoder.encode("campus-demo-2026"), java.time.Clock.systemUTC().instant().toString());
    }
}
