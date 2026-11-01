package edu.must.campuslostfound;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static edu.must.campuslostfound.ClaimModels.*;
import static edu.must.campuslostfound.NoticeModels.Actor;

@Service
public class ClaimService {
    private final JdbcTemplate db;
    private final WorkflowStore store;
    private final NoticeService notices;
    ClaimService(JdbcTemplate db,WorkflowStore store,NoticeService notices) { this.db=db; this.store=store; this.notices=notices; }

    record CaseRow(String id,String foundId,String finderId,String claimantId,String lostId,String evidence,String status,
                   String decisionReason,boolean finderConfirmed,boolean claimantConfirmed,boolean disputed,String disputeReason,
                   long version,int approvalRound,String createdAt,String decidedAt,String completedAt) {}
    record NoticeRow(String id,String authorId,String type,String title,String status,boolean hidden) {}
    private CaseRow row(ResultSet r,int n) throws SQLException {
        return new CaseRow(r.getString("id"),r.getString("found_notice_id"),r.getString("finder_id"),r.getString("claimant_id"),
                r.getString("lost_notice_id"),r.getString("evidence"),r.getString("status"),r.getString("decision_reason"),
                r.getBoolean("finder_confirmed"),r.getBoolean("claimant_confirmed"),r.getBoolean("disputed"),r.getString("dispute_reason"),
                r.getLong("version"),r.getInt("approval_round"),r.getString("created_at"),r.getString("decided_at"),r.getString("completed_at"));
    }
    CaseRow require(String id) {
        var rows=db.query("SELECT * FROM claims WHERE id=?",this::row,id);
        if(rows.isEmpty()) throw ApiProblem.missing();
        return rows.getFirst();
    }
    NoticeRow notice(String id) {
        var rows=db.query("SELECT id,author_id,type,title,status,hidden FROM notices WHERE id=?",(r,n)->
                new NoticeRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getBoolean(6)),id);
        if(rows.isEmpty()) throw ApiProblem.missing();
        return rows.getFirst();
    }
    boolean participant(CaseRow c,Actor actor) { return c.finderId().equals(actor.id())||c.claimantId().equals(actor.id()); }
    boolean moderator(Actor actor) {
        return db.queryForObject("SELECT COUNT(*) FROM accounts WHERE username=? AND role='MODERATOR' AND status='ACTIVE'",Long.class,actor.id())>0;
    }
    String reviewer(String id) {
        var ids=db.queryForList("SELECT a.moderator_id FROM review_assignments a JOIN accounts u ON u.username=a.moderator_id "
                +"WHERE a.claim_id=? AND a.revoked_at IS NULL AND u.role='MODERATOR' AND u.status='ACTIVE'",String.class,id);
        return ids.isEmpty()?null:ids.getFirst();
    }
    boolean assigned(CaseRow c,Actor actor) { return actor.id().equals(reviewer(c.id())); }
    void access(CaseRow c,Actor actor) { if(!participant(c,actor)&&!assigned(c,actor)) throw ApiProblem.forbidden(); }
    private boolean active(CaseRow c) { return List.of("PENDING","HELD","APPROVED").contains(c.status()); }
    private boolean clarification(CaseRow c) {
        return reviewer(c.id())!=null && db.queryForObject("SELECT COUNT(*) FROM reports WHERE claim_id=? AND status='OPEN'",Long.class,c.id())>0;
    }
    private boolean canMessage(CaseRow c) {
        return (active(c)&&!notice(c.foundId()).hidden()&&(c.lostId()==null||!notice(c.lostId()).hidden()))||clarification(c);
    }
    private boolean reserved(String noticeId) {
        return db.queryForObject("SELECT COUNT(*) FROM active_reservations WHERE notice_id=?",Long.class,noticeId)>0;
    }
    private void version(CaseRow c,Long expected) {
        if(expected==null) throw ApiProblem.invalid(Map.of("version","Reload the current case before submitting."));
        if(c.version()!=expected) throw ApiProblem.conflict("This case has changed. Refresh before submitting.");
    }
    private void visibleForAction(CaseRow c) {
        if(notice(c.foundId()).hidden()||(c.lostId()!=null&&notice(c.lostId()).hidden()))
            throw ApiProblem.conflict("The notice is unavailable. Use the dispute review process.");
    }
    ClaimView view(CaseRow c,Actor actor) {
        access(c,actor);
        NoticeRow found=notice(c.foundId());
        String lostTitle=c.lostId()==null?null:notice(c.lostId()).title();
        var names=db.queryForList("SELECT display_name FROM accounts WHERE username=?",String.class,c.claimantId());
        var messages=db.query("SELECT m.*,a.display_name FROM case_messages m LEFT JOIN accounts a ON a.username=m.sender_id WHERE m.claim_id=? ORDER BY m.sequence",
                (r,n)->new CaseMsg(r.getString("id"),r.getString("sender_id"),r.getString("display_name"),r.getString("content"),r.getString("created_at"),r.getString("sender_role"),r.getLong("sequence")),c.id());
        var history=db.query("SELECT * FROM claim_audit WHERE claim_id=? ORDER BY occurred_at,id",(r,n)->
                new CaseEvent(r.getString("action"),r.getString("actor_id"),r.getString("previous_state"),r.getString("new_state"),r.getString("occurred_at")),c.id());
        boolean hidden=found.hidden()||(c.lostId()!=null&&notice(c.lostId()).hidden());
        boolean confirms=participant(c,actor)&&"APPROVED".equals(c.status())&&!c.disputed()&&!hidden
                &&!(c.finderId().equals(actor.id())?c.finderConfirmed():c.claimantConfirmed());
        return new ClaimView(c.id(),c.foundId(),found.title(),c.claimantId(),names.isEmpty()?c.claimantId():names.getFirst(),c.lostId(),lostTitle,
                c.evidence(),c.status(),c.decisionReason(),reviewer(c.id()),c.finderConfirmed(),c.claimantConfirmed(),c.disputed(),c.disputeReason(),
                c.createdAt(),c.decidedAt(),c.completedAt(),c.finderId().equals(actor.id())&&"PENDING".equals(c.status())&&!hidden&&!c.disputed(),
                c.claimantId().equals(actor.id())&&List.of("PENDING","HELD").contains(c.status()),confirms,messages,
                c.version(),c.approvalRound(),canMessage(c),participant(c,actor),hidden,history);
    }
    @Transactional(readOnly=true)
    public ClaimView get(String id,Actor actor) { return view(require(id),actor); }
    @Transactional(readOnly=true)
    public List<ClaimView> list(Actor actor) {
        return db.query("SELECT c.* FROM claims c WHERE c.claimant_id=? OR c.finder_id=? OR EXISTS "
                +"(SELECT 1 FROM review_assignments r JOIN accounts a ON a.username=r.moderator_id WHERE r.claim_id=c.id AND r.moderator_id=? AND r.revoked_at IS NULL AND a.role='MODERATOR' AND a.status='ACTIVE') ORDER BY c.created_at DESC,c.id",this::row,actor.id(),actor.id(),actor.id())
                .stream().map(c->view(c,actor)).toList();
    }
    @Transactional(readOnly=true)
    public List<ClaimView> listForNotice(String foundId,Actor actor) {
        notice(foundId);
        return list(actor).stream().filter(c->c.foundNoticeId().equals(foundId)).toList();
    }
    @Transactional
    public ClaimView submit(SubmitClaim request,String foundId,String key,Actor actor) {
        if(request==null) throw ApiProblem.invalid(Map.of("evidence","Provide private ownership evidence."));
        String evidence=store.text(request.evidence(),"evidence",10,1000);
        String lostId=request.lostNoticeId()==null||request.lostNoticeId().isBlank()?null:request.lostNoticeId().strip();
        SubmitClaim input=new SubmitClaim(lostId,evidence);
        store.gate();
        NoticeRow found=notice(foundId);
        if(!"FOUND".equals(found.type())||found.authorId().equals(actor.id())) throw ApiProblem.forbidden();
        String operation="SUBMIT:"+foundId;
        String prior=store.replay(actor,operation,key,input);
        if(prior!=null) return get(prior,actor);
        if(found.hidden()||!List.of("OPEN","CLAIM_UNDER_REVIEW").contains(found.status())||reserved(foundId))
            throw ApiProblem.conflict("This found notice is not accepting new claims.");
        if(db.queryForObject("SELECT COUNT(*) FROM claims WHERE found_notice_id=? AND claimant_id=? AND status IN ('PENDING','HELD','APPROVED')",Long.class,foundId,actor.id())>0)
            throw ApiProblem.conflict("You already have an unresolved claim for this item.");
        if(lostId!=null) {
            NoticeRow lost=notice(lostId);
            if(!lost.authorId().equals(actor.id())||!"LOST".equals(lost.type())) throw ApiProblem.forbidden();
            if(lost.hidden()||!"OPEN".equals(lost.status())||reserved(lostId)) throw ApiProblem.conflict("Link only your own available Open lost notice.");
        }
        String id=store.id();
        db.update("INSERT INTO claims(id,found_notice_id,finder_id,claimant_id,lost_notice_id,evidence,status,version,approval_round,created_at) VALUES(?,?,?,?,?,?,'PENDING',1,0,?)",
                id,foundId,found.authorId(),actor.id(),lostId,evidence,store.now());
        db.update("INSERT INTO claim_guards(id,found_id,lost_id,status) VALUES(?,?,?,'PENDING')",id,foundId,lostId);
        store.audit(id,actor,"SUBMIT",null,"PENDING");
        notices.workflowTransition(foundId,"CLAIM_UNDER_REVIEW",null,"A private claim is under review.",actor,"CLAIM_SUBMITTED");
        store.remember(actor,operation,key,input,id);
        return get(id,actor);
    }
    @Transactional
    public ClaimView sendMessage(String id,Message request,String key,Actor actor) {
        if(request==null) throw ApiProblem.invalid(Map.of("content","Enter a message."));
        String content=store.text(request.content(),"content",1,1000);
        Message input=new Message(content,request.version());
        store.gate();CaseRow c=require(id);access(c,actor);
        String operation="MESSAGE:"+id;
        if(store.replay(actor,operation,key,input)!=null) return view(c,actor);
        version(c,input.version());
        if(!canMessage(c)) throw ApiProblem.conflict("This case is read-only. An assigned active review permits additional clarification only.");
        long sequence=db.queryForObject("SELECT COALESCE(MAX(sequence),0)+1 FROM case_messages WHERE claim_id=?",Long.class,id);
        String role=c.claimantId().equals(actor.id())?"CLAIMANT":c.finderId().equals(actor.id())?"FINDER":"REVIEWER";
        db.update("INSERT INTO case_messages(id,claim_id,sender_id,sender_role,sequence,content,created_at) VALUES(?,?,?,?,?,?,?)",
                store.id(),id,actor.id(),role,sequence,content,store.now());
        db.update("UPDATE claims SET version=version+1 WHERE id=?",id);
        store.audit(id,actor,"MESSAGE",c.status(),c.status());
        store.remember(actor,operation,key,input,id);return get(id,actor);
    }
    @Transactional
    public ClaimView withdraw(String id,Mutation input,String key,Actor actor) {
        store.gate();CaseRow c=require(id);
        if(!c.claimantId().equals(actor.id())) throw ApiProblem.forbidden();
        String operation="WITHDRAW:"+id;
        if(store.replay(actor,operation,key,input)!=null) return view(c,actor);
        version(c,input==null?null:input.version());
        if(!List.of("PENDING","HELD").contains(c.status())) throw ApiProblem.conflict("Only a Pending or Held claim can be withdrawn. Approval cancellation requires assigned review.");
        setState(c,"WITHDRAWN",actor,"WITHDRAW",null);
        recompute(c.foundId(),actor);
        store.remember(actor,operation,key,input,id);return get(id,actor);
    }
    @Transactional
    public ClaimView decide(String id,Decision request,boolean approve,String key,Actor actor) {
        if(request==null) throw ApiProblem.invalid(Map.of("reason","Give a decision reason."));
        Decision input=new Decision(store.text(request.reason(),"reason",1,1000),request.version());
        store.gate();CaseRow c=require(id);
        if(!c.finderId().equals(actor.id())) throw ApiProblem.forbidden();
        String operation=(approve?"APPROVE:":"REJECT:")+id;
        if(store.replay(actor,operation,key,input)!=null) return view(c,actor);
        version(c,input.version());visibleForAction(c);
        if(!"PENDING".equals(c.status())||c.disputed()) throw ApiProblem.conflict("Only a current Pending claim without an unresolved dispute can be decided.");
        if(approve) {
            NoticeRow found=notice(c.foundId());
            if(!List.of("OPEN","CLAIM_UNDER_REVIEW").contains(found.status())||reserved(c.foundId())) throw ApiProblem.conflict("This item already has an active approval.");
            if(c.lostId()!=null) {
                NoticeRow lost=notice(c.lostId());
                if(!"LOST".equals(lost.type())||!lost.authorId().equals(c.claimantId())||!"OPEN".equals(lost.status())||reserved(c.lostId()))
                    throw ApiProblem.conflict("The linked Lost notice is already reserved or unavailable.");
            }
            // The common transaction gate serialises every workflow; PK(notice_id) also protects both reservation keys.
            db.update("INSERT INTO active_reservations(notice_id,claim_id) VALUES(?,?)",c.foundId(),id);
            if(c.lostId()!=null) db.update("INSERT INTO active_reservations(notice_id,claim_id) VALUES(?,?)",c.lostId(),id);
            db.update("UPDATE claims SET approval_round=approval_round+1,finder_confirmed=FALSE,claimant_confirmed=FALSE,disputed=FALSE,dispute_reason=NULL WHERE id=?",id);
            setState(c,"APPROVED",actor,"APPROVE",input.reason());
            for(CaseRow other:db.query("SELECT * FROM claims WHERE found_notice_id=? AND id<>? AND status='PENDING' ORDER BY id",this::row,c.foundId(),id))
                setState(other,"HELD",actor,"HOLD",null);
            notices.workflowTransition(c.foundId(),"AWAITING_COLLECTION",null,"A claim has been approved; collection is pending.",actor,"CLAIM_APPROVED");
            if(c.lostId()!=null) notices.workflowTransition(c.lostId(),null,null,"This notice is reserved by an approved claim.",actor,"RESERVATION_HELD");
        } else {
            setState(c,"REJECTED",actor,"REJECT",input.reason());recompute(c.foundId(),actor);
        }
        store.remember(actor,operation,key,input,id);return get(id,actor);
    }
    private void setState(CaseRow c,String next,Actor actor,String action,String reason) {
        db.update("UPDATE claims SET status=?,decision_reason=COALESCE(?,decision_reason),decided_at=?,version=version+1 WHERE id=?",next,reason,store.now(),c.id());
        db.update("UPDATE claim_guards SET status=? WHERE id=?",next,c.id());
        store.audit(c.id(),actor,action,c.status(),next);
        if(List.of("APPROVE","REJECT","WITHDRAW","MODERATOR_CANCEL").contains(action)) {
            int round=db.queryForObject("SELECT approval_round FROM claims WHERE id=?",Integer.class,c.id());
            db.update("INSERT INTO claim_decisions(id,claim_id,actor_id,action,reason,approval_round,created_at) VALUES(?,?,?,?,?,?,?)",
                    store.id(),c.id(),actor.id(),action,reason==null?"":reason,round,store.now());
        }
    }
    private void recompute(String foundId,Actor actor) {
        NoticeRow found=notice(foundId);
        if(List.of("RETURNED","CLOSED").contains(found.status())) return;
        long approved=db.queryForObject("SELECT COUNT(*) FROM claims WHERE found_notice_id=? AND status='APPROVED'",Long.class,foundId);
        long pending=db.queryForObject("SELECT COUNT(*) FROM claims WHERE found_notice_id=? AND status IN ('PENDING','HELD')",Long.class,foundId);
        String next=approved>0?"AWAITING_COLLECTION":pending>0?"CLAIM_UNDER_REVIEW":"OPEN";
        if(!next.equals(found.status())) notices.workflowTransition(foundId,next,null,"Claim availability was recalculated.",actor,"CLAIM_RECOMPUTED");
    }
    @Transactional
    public ClaimView confirmReturn(String id,Mutation input,String key,Actor actor) {
        store.gate();CaseRow c=require(id);
        if(!participant(c,actor)) throw ApiProblem.forbidden();
        String operation="CONFIRM:"+id;
        if(store.replay(actor,operation,key,input)!=null) return view(c,actor);
        if(input==null||input.approvalRound()==null||input.approvalRound()!=c.approvalRound())
            throw ApiProblem.conflict("This confirmation belongs to a different approval round. Refresh the case.");
        boolean finder=c.finderId().equals(actor.id());
        // Domain idempotency also covers a deliberate retry using another request key.
        if((finder?c.finderConfirmed():c.claimantConfirmed())&&("APPROVED".equals(c.status())||c.completedAt()!=null)) {
            store.remember(actor,operation,key,input,id);return view(c,actor);
        }
        version(c,input.version());visibleForAction(c);
        if(!"APPROVED".equals(c.status())||c.disputed()) throw ApiProblem.conflict("Return confirmation requires an approved case without an unresolved dispute.");
        if(!"AWAITING_COLLECTION".equals(notice(c.foundId()).status())) throw ApiProblem.conflict("This item is no longer awaiting collection.");
        db.update("INSERT INTO return_confirmations(claim_id,approval_round,party,actor_id,confirmed_at) VALUES(?,?,?,?,?)",id,c.approvalRound(),finder?"FINDER":"CLAIMANT",actor.id(),store.now());
        db.update("UPDATE claims SET "+(finder?"finder_confirmed":"claimant_confirmed")+"=TRUE,version=version+1 WHERE id=?",id);
        store.audit(id,actor,"CONFIRM_RETURN",c.status(),c.status());
        CaseRow changed=require(id);
        if(changed.finderConfirmed()&&changed.claimantConfirmed()) complete(changed,actor);
        store.remember(actor,operation,key,input,id);return get(id,actor);
    }
    private void complete(CaseRow c,Actor actor) {
        // One transaction owns confirmation, both notice transitions, all related guards and reservation release.
        setState(c,"ITEM_RETURNED",actor,"RETURN_COMPLETED",null);
        db.update("UPDATE claims SET completed_at=? WHERE id=?",store.now(),c.id());
        var affected=new LinkedHashSet<String>();
        List<CaseRow> others=c.lostId()==null
                ?db.query("SELECT * FROM claims WHERE found_notice_id=? AND id<>? AND status IN ('PENDING','HELD') ORDER BY id",this::row,c.foundId(),c.id())
                :db.query("SELECT * FROM claims WHERE (found_notice_id=? OR lost_notice_id=?) AND id<>? AND status IN ('PENDING','HELD') ORDER BY id",this::row,c.foundId(),c.lostId(),c.id());
        for(CaseRow other:others) { setState(other,"ITEM_RETURNED",actor,"ITEM_RETURNED",null);affected.add(other.foundId()); }
        db.update("DELETE FROM active_reservations WHERE claim_id=?",c.id());
        notices.workflowTransition(c.foundId(),"RETURNED",null,"Both participants independently confirmed the return.",actor,"RETURN_CONFIRMED");
        if(c.lostId()!=null) notices.workflowTransition(c.lostId(),"CLOSED",null,"Closed after both participants confirmed the linked return.",actor,"RETURN_LINKED");
        for(String id:affected) if(!id.equals(c.foundId())) recompute(id,actor);
    }
    /** Starts a review while retaining the immutable original evidence and any completed return. */
    String openDispute(CaseRow c,String reason,Actor actor) {
        if(!participant(c,actor)) throw ApiProblem.forbidden();
        String reportId=store.id();
        db.update("INSERT INTO reports(id,claim_id,reporter_id,reason,status,version,created_at) VALUES(?,?,?,?,'OPEN',1,?)",reportId,c.id(),actor.id(),reason,store.now());
        db.update("UPDATE claims SET disputed=TRUE,dispute_reason=?,version=version+1 WHERE id=?",reason,c.id());
        store.audit(c.id(),actor,"REPORT_DISPUTE",c.status(),c.status());
        return reportId;
    }
    @Transactional
    public ClaimView dispute(String id,Message request,String key,Actor actor) {
        if(request==null) throw ApiProblem.invalid(Map.of("content","Explain the dispute."));
        Message input=new Message(store.text(request.content(),"content",1,1000),request.version());
        store.gate();CaseRow c=require(id);
        if(!participant(c,actor)) throw ApiProblem.forbidden();
        String operation="DISPUTE:"+id;
        if(store.replay(actor,operation,key,input)!=null) return view(c,actor);
        version(c,input.version());openDispute(c,input.content(),actor);
        store.remember(actor,operation,key,input,id);return get(id,actor);
    }
    void cancelApproval(CaseRow c,String reason,Actor actor) {
        if(!assigned(c,actor)||!moderator(actor)) throw ApiProblem.forbidden();
        if(!"APPROVED".equals(c.status())) throw ApiProblem.conflict("Only an active approval can be cancelled. Completed returns remain unchanged.");
        setState(c,"WITHDRAWN",actor,"MODERATOR_CANCEL",reason);
        db.update("UPDATE claims SET finder_confirmed=FALSE,claimant_confirmed=FALSE WHERE id=?",c.id());
        db.update("UPDATE return_confirmations SET invalidated_at=? WHERE claim_id=? AND approval_round=? AND invalidated_at IS NULL",store.now(),c.id(),c.approvalRound());
        db.update("DELETE FROM active_reservations WHERE claim_id=?",c.id());
        for(CaseRow other:db.query("SELECT * FROM claims WHERE found_notice_id=? AND status='HELD' ORDER BY id",this::row,c.foundId()))
            setState(other,"PENDING",actor,"RELEASE_HOLD",null);
        recompute(c.foundId(),actor);
        if(c.lostId()!=null) notices.workflowTransition(c.lostId(),null,null,"The previous reservation was released.",actor,"RESERVATION_RELEASE");
    }
    void clearDispute(CaseRow c,Actor actor) {
        if(db.queryForObject("SELECT COUNT(*) FROM reports WHERE claim_id=? AND status='OPEN'",Long.class,c.id())==0) {
            db.update("UPDATE claims SET disputed=FALSE,version=version+1 WHERE id=?",c.id());
            store.audit(c.id(),actor,"DISPUTE_RESOLVED",c.status(),c.status());
        }
    }
}
