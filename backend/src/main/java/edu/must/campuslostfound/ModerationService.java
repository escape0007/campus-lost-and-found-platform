package edu.must.campuslostfound;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static edu.must.campuslostfound.ClaimModels.*;
import static edu.must.campuslostfound.NoticeModels.Actor;

@Service
public class ModerationService {
    private final JdbcTemplate db;
    private final WorkflowStore store;
    private final ClaimService claims;
    private final NoticeService notices;
    ModerationService(JdbcTemplate db,WorkflowStore store,ClaimService claims,NoticeService notices) {
        this.db=db;this.store=store;this.claims=claims;this.notices=notices;
    }
    record Row(String id,String noticeId,String claimId,String reporterId,String reason,String status,String resolution,
               String moderatorId,String resolutionNote,long version,String createdAt,String resolvedAt) {
        String targetId() { return claimId==null?noticeId:claimId; }
        String targetKey() { return (claimId==null?"NOTICE:":"CLAIM:")+targetId(); }
    }
    private Row require(String id) {
        var rows=db.query("SELECT * FROM reports WHERE id=?",(r,n)->new Row(r.getString("id"),r.getString("notice_id"),r.getString("claim_id"),
                r.getString("reporter_id"),r.getString("reason"),r.getString("status"),r.getString("resolution"),r.getString("moderator_id"),
                r.getString("resolution_note"),r.getLong("version"),r.getString("created_at"),r.getString("resolved_at")),id);
        if(rows.isEmpty()) throw ApiProblem.missing();return rows.getFirst();
    }
    private void moderator(Actor actor) { if(!claims.moderator(actor)) throw ApiProblem.forbidden(); }
    private String reviewer(Row r) {
        var ids=db.queryForList("SELECT x.moderator_id FROM review_assignments x JOIN accounts a ON a.username=x.moderator_id "
                +"WHERE x.active_target_key=? AND x.revoked_at IS NULL AND a.role='MODERATOR' AND a.status='ACTIVE'",String.class,r.targetKey());
        return ids.isEmpty()?null:ids.getFirst();
    }
    private boolean participant(Row r,Actor actor) {
        return r.claimId()!=null?claims.participant(claims.require(r.claimId()),actor):claims.notice(r.noticeId()).authorId().equals(actor.id());
    }
    private boolean assigned(Row r,Actor actor) { return actor.id().equals(reviewer(r)); }
    private ReportView view(Row r,Actor actor) {
        boolean isModerator=claims.moderator(actor), own=r.reporterId().equals(actor.id()), designated=assigned(r,actor);
        if(!isModerator&&!own) throw ApiProblem.forbidden();
        boolean privateAccess=designated||own;
        String title=r.claimId()==null?claims.notice(r.noticeId()).title():claims.notice(claims.require(r.claimId()).foundId()).title();
        String reviewer=reviewer(r);
        return new ReportView(r.id(),r.claimId()==null?"NOTICE":"CLAIM_DISPUTE",r.targetId(),privateAccess?r.reporterId():null,
                privateAccess?r.reason():null,r.status(),r.resolution(),privateAccess?r.resolutionNote():null,r.createdAt(),r.resolvedAt(),title,
                r.version(),reviewer,isModerator&&"OPEN".equals(r.status())&&reviewer==null&&!participant(r,actor)&&!own,
                designated&&"OPEN".equals(r.status()));
    }
    @Transactional(readOnly=true)
    public ReportView get(String id,Actor actor) { return view(require(id),actor); }
    @Transactional(readOnly=true)
    public List<ReportView> list(Actor actor) {
        moderator(actor);
        return db.queryForList("SELECT id FROM reports ORDER BY created_at DESC,id",String.class).stream().map(id->view(require(id),actor)).toList();
    }
    @Transactional
    public ReportView reportNotice(String id,Report request,String key,Actor actor) {
        if(request==null) throw ApiProblem.invalid(Map.of("reason","Explain the report."));
        Report input=new Report(store.text(request.reason(),"reason",1,1000));
        store.gate();
        var notice=claims.notice(id);
        String operation="REPORT_NOTICE:"+id;
        String previous=store.replay(actor,operation,key,input);
        if(previous!=null) return get(previous,actor);
        if(notice.hidden()) throw ApiProblem.missing();
        String reportId=store.id();
        db.update("INSERT INTO reports(id,notice_id,reporter_id,reason,status,version,created_at) VALUES(?,?,?,?,'OPEN',1,?)",reportId,id,actor.id(),input.reason(),store.now());
        audit(reportId,actor,"REPORT_NOTICE");
        store.remember(actor,operation,key,input,reportId);return get(reportId,actor);
    }
    @Transactional
    public ReportView reportClaimDispute(String id,Report request,String key,Actor actor) {
        if(request==null) throw ApiProblem.invalid(Map.of("reason","Explain the dispute."));
        Report input=new Report(store.text(request.reason(),"reason",1,1000));
        store.gate();var claim=claims.require(id);
        if(!claims.participant(claim,actor)) throw ApiProblem.forbidden();
        String operation="REPORT_CLAIM:"+id;
        String previous=store.replay(actor,operation,key,input);
        if(previous!=null) return get(previous,actor);
        String reportId=claims.openDispute(claim,input.reason(),actor);
        audit(reportId,actor,"REPORT_DISPUTE");
        store.remember(actor,operation,key,input,reportId);return get(reportId,actor);
    }
    /** A moderator explicitly accepts an unassigned queue item. Queue metadata alone never grants case access. */
    @Transactional
    public ReportView assign(String id,Assignment request,String key,Actor actor) {
        moderator(actor);
        if(request==null) throw ApiProblem.invalid(Map.of("reason","Record an assignment reason."));
        Assignment input=new Assignment(store.text(request.reason(),"reason",1,1000));
        store.gate();Row r=require(id);
        if(participant(r,actor)||r.reporterId().equals(actor.id())) throw ApiProblem.forbidden();
        String operation="ASSIGN:"+id;
        if(store.replay(actor,operation,key,input)!=null) return view(r,actor);
        if(!"OPEN".equals(r.status())) throw ApiProblem.conflict("This report is no longer open.");
        String current=reviewer(r);
        if(current!=null&&!current.equals(actor.id())) throw ApiProblem.conflict("Another moderator is already assigned to this target.");
        if(current==null) {
            // A disabled/demoted old assignee is revoked before a new effective assignment is created.
            db.update("UPDATE review_assignments SET revoked_at=?,revoked_reason=?,active_target_key=NULL WHERE active_target_key=?",store.now(),"Previous moderator is no longer eligible.",r.targetKey());
            db.update("INSERT INTO review_assignments(id,notice_id,claim_id,moderator_id,assigned_by,reason,assigned_at,active_target_key) VALUES(?,?,?,?,?,?,?,?)",
                    store.id(),r.noticeId(),r.claimId(),actor.id(),actor.id(),input.reason(),store.now(),r.targetKey());
            bumpTargetReports(r);
            audit(id,actor,"ASSIGN");
            if(r.claimId()!=null) store.audit(r.claimId(),actor,"REVIEW_ASSIGNED",null,null);
        }
        store.remember(actor,operation,key,input,id);return get(id,actor);
    }
    @Transactional
    public ReportView release(String id,Assignment request,String key,Actor actor) {
        moderator(actor);
        if(request==null) throw ApiProblem.invalid(Map.of("reason","Record a release reason."));
        Assignment input=new Assignment(store.text(request.reason(),"reason",1,1000));
        store.gate();Row r=require(id);
        String operation="RELEASE:"+id;
        // A revoked assignee may retrieve only their own idempotent receipt (the view is now redacted).
        if(store.replay(actor,operation,key,input)!=null) return view(r,actor);
        if(!assigned(r,actor)) throw ApiProblem.forbidden();
        revoke(r,actor,input.reason());bumpTargetReports(r);
        audit(id,actor,"RELEASE_ASSIGNMENT");
        store.remember(actor,operation,key,input,id);return get(id,actor);
    }
    @Transactional
    public ReportView resolve(String id,ResolveReport request,String key,Actor actor) {
        moderator(actor);
        if(request==null||request.resolution()==null||!List.of("DISMISS","HIDE_NOTICE","CANCEL_APPROVAL","RESUME_COLLECTION").contains(request.resolution()))
            throw ApiProblem.invalid(Map.of("resolution","Choose a supported resolution."));
        ResolveReport input=new ResolveReport(request.resolution(),store.text(request.note(),"note",1,1000),request.version());
        store.gate();Row r=require(id);
        String operation="RESOLVE:"+id;
        // Replay is an authenticated receipt, not renewed access to the private case or its reasons.
        if(store.replay(actor,operation,key,input)!=null) return view(r,actor);
        if(!assigned(r,actor)) throw ApiProblem.forbidden();
        if(input.version()==null) throw ApiProblem.invalid(Map.of("version","Reload the report before resolving it."));
        if(r.version()!=input.version()||!"OPEN".equals(r.status())) throw ApiProblem.conflict("This report has changed. Refresh the queue.");
        ClaimService.CaseRow c=r.claimId()==null?null:claims.require(r.claimId());
        switch(input.resolution()) {
            case "HIDE_NOTICE" -> {
                if(c!=null) throw ApiProblem.invalid(Map.of("resolution","Hiding requires a notice report; claim assignment does not grant notice moderation."));
                if(!claims.notice(r.noticeId()).hidden()) notices.workflowTransition(r.noticeId(),null,true,"This notice is unavailable after review.",actor,"MODERATION_HIDE");
            }
            case "CANCEL_APPROVAL" -> {
                if(c==null) throw ApiProblem.invalid(Map.of("resolution","Cancellation requires an assigned claim report."));
                claims.cancelApproval(c,input.note(),actor);
            }
            case "RESUME_COLLECTION" -> {
                if(c==null||!"APPROVED".equals(c.status())||!c.disputed()) throw ApiProblem.conflict("Only a disputed approved claim can resume collection.");
                if(claims.notice(c.foundId()).hidden()||(c.lostId()!=null&&claims.notice(c.lostId()).hidden()))
                    throw ApiProblem.conflict("A hidden notice cannot resume collection.");
                // Consent is collected again after review, rather than reusing a pre-dispute confirmation.
                db.update("UPDATE return_confirmations SET invalidated_at=? WHERE claim_id=? AND invalidated_at IS NULL",store.now(),c.id());
                db.update("UPDATE claims SET finder_confirmed=FALSE,claimant_confirmed=FALSE,approval_round=approval_round+1,version=version+1 WHERE id=?",c.id());
                store.audit(c.id(),actor,"RESUME_COLLECTION",c.status(),c.status());
            }
            default -> { /* Dismissal records a reviewed outcome without altering the return fact. */ }
        }
        db.update("UPDATE reports SET status=?,resolution=?,moderator_id=?,resolution_note=?,resolved_at=?,version=version+1 WHERE id=?",
                "DISMISS".equals(input.resolution())?"DISMISSED":"RESOLVED",input.resolution(),actor.id(),input.note(),store.now(),id);
        audit(id,actor,input.resolution());
        if(c!=null) claims.clearDispute(claims.require(c.id()),actor);
        long remaining=db.queryForObject("SELECT COUNT(*) FROM reports WHERE "+(r.claimId()==null?"notice_id":"claim_id")+"=? AND status='OPEN'",Long.class,r.targetId());
        if(remaining==0) revoke(r,actor,"All reports for this target have been resolved.");
        store.remember(actor,operation,key,input,id);return get(id,actor);
    }
    private void bumpTargetReports(Row r) {
        db.update("UPDATE reports SET version=version+1 WHERE "+(r.claimId()==null?"notice_id":"claim_id")+"=? AND status='OPEN'",r.targetId());
    }
    private void revoke(Row r,Actor actor,String reason) {
        db.update("UPDATE review_assignments SET revoked_at=?,revoked_reason=?,active_target_key=NULL WHERE active_target_key=?",store.now(),reason,r.targetKey());
        if(r.claimId()!=null) store.audit(r.claimId(),actor,"REVIEW_REVOKED",null,null);
    }
    private void audit(String reportId,Actor actor,String action) {
        db.update("INSERT INTO moderation_audit(id,report_id,actor_id,action,occurred_at) VALUES(?,?,?,?,?)",store.id(),reportId,actor.id(),action,store.now());
    }
}
