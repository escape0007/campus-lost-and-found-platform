package edu.must.campuslostfound;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static edu.must.campuslostfound.NoticeModels.*;
import static edu.must.campuslostfound.ClaimModels.*;

@Service
public class ModerationService {
    private final JdbcTemplate db;
    private final Clock clock;
    ModerationService(JdbcTemplate db, Clock clock) { this.db=db; this.clock=clock; }
    private String now() { return clock.instant().toString(); }
    private int len(String s) { return s==null?0:s.codePointCount(0,s.length()); }

    private void requireModerator(Actor actor) {
        Long mod=db.queryForObject("SELECT COUNT(*) FROM accounts WHERE username=? AND role='MODERATOR'",Long.class,actor.id());
        if(mod==0) throw ApiProblem.forbidden();
    }

    @Transactional
    public ReportView reportNotice(String noticeId,Report in,Actor actor) {
        if(in==null||in.reason()==null||in.reason().isBlank()) throw ApiProblem.invalid(Map.of("reason","Describe the issue."));
        String reason=in.reason().strip();
        if(len(reason)<5||len(reason)>1000) throw ApiProblem.invalid(Map.of("reason","Use 5–1,000 characters."));
        Long exists=db.queryForObject("SELECT COUNT(*) FROM notices WHERE id=?",Long.class,noticeId);
        if(exists==0) throw ApiProblem.missing();
        return insertReport("NOTICE",noticeId,reason,actor);
    }

    @Transactional
    public ReportView reportClaimDispute(String claimId,Report in,Actor actor) {
        if(in==null||in.reason()==null||in.reason().isBlank()) throw ApiProblem.invalid(Map.of("reason","Describe the dispute."));
        String reason=in.reason().strip();
        if(len(reason)<5||len(reason)>1000) throw ApiProblem.invalid(Map.of("reason","Use 5–1,000 characters."));
        Long exists=db.queryForObject("SELECT COUNT(*) FROM claims WHERE id=?",Long.class,claimId);
        if(exists==0) throw ApiProblem.missing();
        return insertReport("CLAIM",claimId,reason,actor);
    }

    private ReportView insertReport(String type,String targetId,String reason,Actor actor) {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO reports(id,target_type,target_id,reporter_id,reason,status,created_at) VALUES(?,?,?,?,'OPEN',?)",
                id,type,targetId,actor.id(),reason,now());
        return view(id);
    }

    private ReportView view(String id) {
        var rows=db.query("SELECT r.*, COALESCE(n.title, (SELECT n2.title FROM claims c2 JOIN notices n2 ON n2.id=c2.found_notice_id WHERE c2.id=r.target_id)) AS target_title FROM reports r LEFT JOIN notices n ON n.id=r.target_id WHERE r.id=?",
                this::reportRow,id);
        if(rows.isEmpty()) throw ApiProblem.missing();
        return rows.getFirst();
    }

    private ReportView reportRow(ResultSet r,int n) throws SQLException {
        return new ReportView(r.getString("id"),r.getString("target_type"),r.getString("target_id"),
                r.getString("reporter_id"),r.getString("reason"),r.getString("status"),
                r.getString("resolution"),r.getString("resolution_note"),
                r.getString("created_at"),r.getString("resolved_at"),r.getString("target_title"));
    }

    @Transactional(readOnly=true)
    public List<ReportView> list(Actor actor) {
        requireModerator(actor);
        return db.query("SELECT r.*, COALESCE(n.title, (SELECT n2.title FROM claims c2 JOIN notices n2 ON n2.id=c2.found_notice_id WHERE c2.id=r.target_id)) AS target_title FROM reports r LEFT JOIN notices n ON n.id=r.target_id ORDER BY r.created_at DESC",
                this::reportRow);
    }

    @Transactional
    public ReportView resolve(String reportId,ResolveReport in,Actor actor) {
        requireModerator(actor);
        if(in==null||in.resolution()==null) throw ApiProblem.invalid(Map.of("resolution","Choose a resolution."));
        String res=in.resolution().toUpperCase();
        if(!List.of("HIDE_NOTICE","CANCEL_APPROVAL","DISMISS").contains(res))
            throw ApiProblem.invalid(Map.of("resolution","Choose HIDE_NOTICE, CANCEL_APPROVAL or DISMISS."));
        var rows=db.query("SELECT * FROM reports WHERE id=? FOR UPDATE",(r,n)->Map.of(
                "targetType",r.getString("target_type"),"targetId",r.getString("target_id"),"status",r.getString("status")),reportId);
        if(rows.isEmpty()) throw ApiProblem.missing();
        if(!"OPEN".equals(rows.getFirst().get("status"))) throw ApiProblem.conflict("This report is already resolved.");
        String targetType=(String)rows.getFirst().get("targetType");
        String targetId=(String)rows.getFirst().get("targetId");
        String note=in.note()==null?"":in.note().strip();
        String time=now();
        if("HIDE_NOTICE".equals(res)) {
            db.update("UPDATE notices SET hidden=TRUE,updated_at=? WHERE id=?",time,targetId);
        } else if("CANCEL_APPROVAL".equals(res)) {
            // cancel an approved claim: revert found notice to OPEN, release reservation
            db.query("SELECT found_notice_id FROM claims WHERE id=?",(r,n)->r.getString(1),targetId).forEach(foundId->{
                db.update("UPDATE claims SET status='REJECTED',decision_reason=?,reviewer_id=?,decided_at=? WHERE id=?",
                        "Approval cancelled by moderator: "+note,actor.id(),time,targetId);
                db.update("UPDATE notices SET status='OPEN',updated_at=?,version=version+1 WHERE id=? AND status='AWAITING_COLLECTION'",time,foundId);
                db.update("DELETE FROM active_reservations WHERE notice_id=?",foundId);
            });
        }
        db.update("UPDATE reports SET status=?,resolution=?,moderator_id=?,resolution_note=?,resolved_at=? WHERE id=?",
                "DISMISS".equals(res)?"DISMISSED":"RESOLVED",res,actor.id(),note,time,reportId);
        return view(reportId);
    }
}
