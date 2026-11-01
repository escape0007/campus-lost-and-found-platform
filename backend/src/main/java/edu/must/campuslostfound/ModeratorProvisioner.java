package edu.must.campuslostfound;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit local operator configuration only; registration cannot set a role. */
@Component
class ModeratorProvisioner implements ApplicationRunner {
    private final JdbcTemplate db;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final String grantNames, revokeNames;
    ModeratorProvisioner(JdbcTemplate db,Clock clock,PlatformTransactionManager manager,
                         @Value("${app.moderator-usernames:}") String grantNames,
                         @Value("${app.moderator-revoke-usernames:}") String revokeNames) {
        this.db=db;this.clock=clock;this.transactions=new TransactionTemplate(manager);
        this.grantNames=grantNames;this.revokeNames=revokeNames;
    }
    @Override public void run(ApplicationArguments arguments) {
        Set<String> grants=parse(grantNames), revokes=parse(revokeNames);
        if(grants.isEmpty()&&revokes.isEmpty()) return;
        if(grants.stream().anyMatch(revokes::contains)) throw new IllegalArgumentException("Moderator grant and revoke lists overlap.");
        transactions.executeWithoutResult(tx->{
            db.queryForObject("SELECT counter FROM commit_order WHERE id=1 FOR UPDATE",Long.class);
            for(String username:grants) configure(username,true);
            for(String username:revokes) configure(username,false);
        });
    }
    private Set<String> parse(String raw) {
        Set<String> result=new LinkedHashSet<>();
        if(raw==null||raw.isBlank()) return result;
        for(String item:raw.split(",")) {
            String username=item.strip().toLowerCase(Locale.ROOT);
            if(!username.matches("[a-z0-9_]{3,32}")) throw new IllegalArgumentException("Moderator configuration contains an invalid username.");
            result.add(username);
        }
        return result;
    }
    private void configure(String key,boolean grant) {
        var rows=db.query("SELECT username,role,status FROM accounts WHERE username_key=?",(r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)},key);
        if(rows.isEmpty()||!"ACTIVE".equals(rows.getFirst()[2]))
            throw new IllegalStateException("Moderator configuration requires an existing active account: "+key);
        String[] row=rows.getFirst();String role=grant?"MODERATOR":"STUDENT";
        if(role.equals(row[1])) return;
        db.update("UPDATE accounts SET role=? WHERE username_key=?",role,key);
        if(!grant) db.update("UPDATE review_assignments SET revoked_at=?,revoked_reason='Local operator removed the moderator role.',active_target_key=NULL WHERE moderator_id=? AND revoked_at IS NULL",clock.instant().toString(),row[0]);
        db.update("INSERT INTO account_audit(id,actor_id,action,target,outcome,occurred_at) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(),"local-operator",grant?"MODERATOR_GRANTED":"MODERATOR_REVOKED",row[0],"SUCCESS",clock.instant().toString());
    }
}
