package edu.must.campuslostfound;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static edu.must.campuslostfound.NoticeModels.Actor;

/** Shared serialisation/idempotency boundary, always used inside the business transaction. */
@Component
class WorkflowStore {
    final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    WorkflowStore(JdbcTemplate db, ObjectMapper json, Clock clock) { this.db=db; this.json=json; this.clock=clock; }
    void gate() { db.queryForObject("SELECT counter FROM commit_order WHERE id=1 FOR UPDATE", Long.class); }
    String now() { return clock.instant().toString(); }
    String id() { return UUID.randomUUID().toString(); }
    String text(String value, String field, int minimum, int maximum) {
        String result=value==null?"":value.strip();
        int length=result.codePointCount(0,result.length());
        if(length<minimum||length>maximum) throw ApiProblem.invalid(Map.of(field,"Use "+minimum+"–"+maximum+" Unicode characters."));
        return result;
    }
    private String hash(Object payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(payload))); }
        catch(Exception e) { throw new IllegalStateException("Cannot fingerprint workflow request",e); }
    }
    String replay(Actor actor,String operation,String key,Object payload) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,128}"))
            throw ApiProblem.invalid(Map.of("requestKey","A valid Idempotency-Key is required."));
        var rows=db.query("SELECT request_hash,outcome_reference FROM claim_requests WHERE actor_id=? AND operation=? AND request_key=?",
                (r,n)->new String[]{r.getString(1),r.getString(2)},actor.id(),operation,key);
        if(rows.isEmpty()) return null;
        if(!rows.getFirst()[0].equals(hash(payload))) throw ApiProblem.conflict("This request key was used with different content. Use a new key.");
        return rows.getFirst()[1];
    }
    void remember(Actor actor,String operation,String key,Object payload,String result) {
        db.update("INSERT INTO claim_requests(actor_id,operation,request_key,request_hash,outcome_reference,created_at) VALUES(?,?,?,?,?,?)",
                actor.id(),operation,key,hash(payload),result,now());
    }
    void audit(String claimId,Actor actor,String action,String previous,String next) {
        db.update("INSERT INTO claim_audit(id,claim_id,actor_id,action,previous_state,new_state,occurred_at) VALUES(?,?,?,?,?,?,?)",
                id(),claimId,actor.id(),action,previous,next,now());
    }
}
