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
public class ClaimService {
    private final JdbcTemplate db;
    private final Clock clock;
    ClaimService(JdbcTemplate db, Clock clock) { this.db=db; this.clock=clock; }
    private String now() { return clock.instant().toString(); }
    private int len(String s) { return s==null?0:s.codePointCount(0,s.length()); }

    private record ClaimRow(String id,String foundId,String claimantId,String claimantName,String lostId,
                            String evidence,String status,String decisionReason,String reviewerId,
                            boolean finderConfirmed,boolean claimantConfirmed,boolean disputed,String disputeReason,
                            String createdAt,String decidedAt,String completedAt) {}

    private ClaimRow claimRow(ResultSet r,int n) throws SQLException {
        return new ClaimRow(r.getString("id"),r.getString("found_notice_id"),r.getString("claimant_id"),
                r.getString("claimant_name"),r.getString("lost_notice_id"),r.getString("evidence"),
                r.getString("status"),r.getString("decision_reason"),r.getString("reviewer_id"),
                r.getBoolean("finder_confirmed"),r.getBoolean("claimant_confirmed"),r.getBoolean("disputed"),
                r.getString("dispute_reason"),r.getString("created_at"),r.getString("decided_at"),r.getString("completed_at"));
    }

    private record NoticeRow(String id,String authorId,String authorName,String type,String title,String status,boolean hidden) {}
    private NoticeRow noticeRow(ResultSet r,int n) throws SQLException {
        return new NoticeRow(r.getString("id"),r.getString("author_id"),r.getString("author_name"),
                r.getString("type"),r.getString("title"),r.getString("status"),r.getBoolean("hidden"));
    }

    private NoticeRow requireNotice(String id,boolean lock) {
        var rows=db.query("SELECT * FROM notices WHERE id=?"+(lock?" FOR UPDATE":""),this::noticeRow,id);
        if(rows.isEmpty()||rows.getFirst().hidden()) throw ApiProblem.missing();
        return rows.getFirst();
    }

    private ClaimRow requireClaim(String id,boolean lock) {
        var rows=db.query("SELECT c.*,a.display_name AS claimant_name FROM claims c JOIN accounts a ON a.username=c.claimant_id WHERE c.id=?"+(lock?" FOR UPDATE":""),
                this::claimRow,id);
        if(rows.isEmpty()) throw ApiProblem.missing();
        return rows.getFirst();
    }

    private boolean isVisible(ClaimRow c, Actor actor) {
        if(c.claimantId().equals(actor.id())) return true;
        var found=requireNotice(c.foundId(),false);
        if(found.authorId().equals(actor.id())) return true;
        // moderators can view any active case
        return db.queryForObject("SELECT COUNT(*) FROM accounts WHERE username=? AND role='MODERATOR'",Long.class,actor.id())>0;
    }

    private void gateAccess(ClaimRow c, Actor actor) {
        if(!isVisible(c,actor)) throw ApiProblem.missing();
    }

    private List<CaseMsg> messages(String claimId) {
        return db.query("SELECT m.id,m.sender_id,a.display_name AS sender_name,m.content,m.created_at FROM case_messages m JOIN accounts a ON a.username=m.sender_id WHERE m.claim_id=? ORDER BY m.created_at,m.id",
                (r,n)->new CaseMsg(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)),claimId);
    }

    private ClaimView view(ClaimRow c, Actor actor) {
        var found=requireNotice(c.foundId(),false);
        String lostTitle = c.lostId()==null?null:requireNotice(c.lostId(),false).title();
        var foundAuthor=found.authorId();
        boolean canDecide = foundAuthor.equals(actor.id()) && ("PENDING".equals(c.status())||"HELD".equals(c.status()));
        boolean canWithdraw = c.claimantId().equals(actor.id()) && ("PENDING".equals(c.status())||"HELD".equals(c.status()));
        boolean canConfirm = ("APPROVED".equals(c.status())) && (foundAuthor.equals(actor.id())||c.claimantId().equals(actor.id()));
        return new ClaimView(c.id(),c.foundId(),found.title(),c.claimantId(),c.claimantName(),
                c.lostId(),lostTitle,c.evidence(),c.status(),c.decisionReason(),c.reviewerId(),
                c.finderConfirmed(),c.claimantConfirmed(),c.disputed(),c.disputeReason(),
                c.createdAt(),c.decidedAt(),c.completedAt(),canDecide,canWithdraw,canConfirm,messages(c.id()));
    }

    @Transactional
    public ClaimView submit(SubmitClaim in,String foundId,Actor actor) {
        if(in==null) throw ApiProblem.invalid(Map.of("claim","Claim details are required."));
        String evidence=in.evidence()==null?"":in.evidence().strip();
        if(len(evidence)<10||len(evidence)>1000) throw ApiProblem.invalid(Map.of("evidence","Use 10–1,000 Unicode characters of private ownership evidence."));
        var found=requireNotice(foundId,true);
        if(!"FOUND".equals(found.type())) throw ApiProblem.invalid(Map.of("notice","You can only claim a found-item notice."));
        if(found.authorId().equals(actor.id())) throw ApiProblem.invalid(Map.of("claim","You cannot claim your own found item."));
        if(!"OPEN".equals(found.status())&&!"CLAIM_UNDER_REVIEW".equals(found.status())) throw ApiProblem.conflict("This found item is not available for claiming.");
        // already an approved claim on this item?
        if(db.queryForObject("SELECT COUNT(*) FROM claims WHERE found_notice_id=? AND status IN ('APPROVED','AWAITING_COLLECTION','RETURNED')",Long.class,foundId)>0)
            throw ApiProblem.conflict("This item already has an approved claim.");
        // same user already has a pending/held claim on this item
        if(db.queryForObject("SELECT COUNT(*) FROM claims WHERE found_notice_id=? AND claimant_id=? AND status IN ('PENDING','HELD')",Long.class,foundId,actor.id())>0)
            throw ApiProblem.conflict("You already have a pending claim on this item.");
        String lostId=null;
        if(in.lostNoticeId()!=null&&!in.lostNoticeId().isBlank()) {
            lostId=in.lostNoticeId().trim();
            var lost=requireNotice(lostId,true);
            if(!"LOST".equals(lost.type())) throw ApiProblem.invalid(Map.of("lostNoticeId","Link one of your own lost-item notices."));
            if(!lost.authorId().equals(actor.id())) throw ApiProblem.forbidden();
            if(!"OPEN".equals(lost.status())) throw ApiProblem.invalid(Map.of("lostNoticeId","Your lost notice must be open."));
        }
        String id=UUID.randomUUID().toString(), time=now();
        db.update("INSERT INTO claims(id,found_notice_id,claimant_id,lost_notice_id,evidence,status,created_at) VALUES(?,?,?,?,?,'PENDING',?)",
                id,foundId,actor.id(),lostId,evidence,time);
        // update claim_guards seam
        db.update("INSERT INTO claim_guards(id,found_id,lost_id,status) VALUES(?,?,?,'PENDING')",UUID.randomUUID().toString(),foundId,lostId);
        // if this was OPEN, move to CLAIM_UNDER_REVIEW
        if("OPEN".equals(found.status())) {
            db.update("UPDATE notices SET status='CLAIM_UNDER_REVIEW',updated_at=?,version=version+1 WHERE id=?",time,foundId);
        }
        return view(requireClaim(id,false),actor);
    }

    @Transactional(readOnly=true)
    public ClaimView get(String claimId,Actor actor) {
        var c=requireClaim(claimId,false);
        gateAccess(c,actor);
        return view(c,actor);
    }

    @Transactional(readOnly=true)
    public java.util.List<ClaimView> listForNotice(String noticeId,Actor actor) {
        var rows=db.query("SELECT c.*,a.display_name AS claimant_name FROM claims c JOIN accounts a ON a.username=c.claimant_id WHERE c.found_notice_id=? AND (c.claimant_id=? OR c.found_notice_id IN (SELECT id FROM notices WHERE author_id=?) OR EXISTS(SELECT 1 FROM accounts WHERE username=? AND role='MODERATOR')) ORDER BY c.created_at",
                this::claimRow,noticeId,actor.id(),actor.id(),actor.id());
        return rows.stream().map(c->view(c,actor)).toList();
    }

    @Transactional
    public ClaimView sendMessage(String claimId,Message in,Actor actor) {
        if(in==null||in.content()==null||in.content().isBlank()) throw ApiProblem.invalid(Map.of("content","Message cannot be empty."));
        String content=in.content().strip();
        if(len(content)<1||len(content)>1000) throw ApiProblem.invalid(Map.of("content","Use 1–1,000 Unicode characters."));
        var c=requireClaim(claimId,true);
        gateAccess(c,actor);
        db.update("INSERT INTO case_messages(id,claim_id,sender_id,content,created_at) VALUES(?,?,?,?,?)",
                UUID.randomUUID().toString(),claimId,actor.id(),content,now());
        return view(requireClaim(claimId,false),actor);
    }

    @Transactional
    public ClaimView withdraw(String claimId,Actor actor) {
        var c=requireClaim(claimId,true);
        if(!c.claimantId().equals(actor.id())) throw ApiProblem.forbidden();
        if(!"PENDING".equals(c.status())&&!"HELD".equals(c.status())) throw ApiProblem.conflict("Only pending or held claims can be withdrawn.");
        db.update("UPDATE claims SET status='WITHDRAWN',decided_at=? WHERE id=?",now(),claimId);
        db.update("UPDATE claim_guards SET status='WITHDRAWN' WHERE found_id=? AND lost_id IS NOT NULL AND status='PENDING'",c.foundId());
        releaseIfNoActive(c.foundId());
        return view(requireClaim(claimId,false),actor);
    }

    @Transactional
    public ClaimView approve(String claimId,Decision in,Actor actor) {
        if(in==null||in.reason()==null||in.reason().isBlank()) throw ApiProblem.invalid(Map.of("reason","Give a reason for approving."));
        String reason=in.reason().strip();
        if(len(reason)>1000) throw ApiProblem.invalid(Map.of("reason","Reason must be at most 1,000 characters."));
        return doDecide(claimId,reason,actor,true);
    }

    @Transactional
    public ClaimView reject(String claimId,Decision in,Actor actor) {
        if(in==null||in.reason()==null||in.reason().isBlank()) throw ApiProblem.invalid(Map.of("reason","Give a reason for rejecting."));
        String reason=in.reason().strip();
        if(len(reason)>1000) throw ApiProblem.invalid(Map.of("reason","Reason must be at most 1,000 characters."));
        return doDecide(claimId,reason,actor,false);
    }

    private ClaimView doDecide(String claimId,String reason,Actor actor,boolean approve) {
        var c=requireClaim(claimId,true);
        var found=requireNotice(c.foundId(),true);
        if(!found.authorId().equals(actor.id())) throw ApiProblem.forbidden();
        if(!"PENDING".equals(c.status())&&!"HELD".equals(c.status())) throw ApiProblem.conflict("This claim has already been decided.");
        String time=now();
        if(approve) {
            // check no other APPROVED exists on this found item
            Long others=db.queryForObject("SELECT COUNT(*) FROM claims WHERE found_notice_id=? AND status='APPROVED'",Long.class,c.foundId());
            if(others>0) throw ApiProblem.conflict("Another claim has already been approved for this item.");
            db.update("UPDATE claims SET status='APPROVED',decision_reason=?,reviewer_id=?,decided_at=? WHERE id=?",reason,actor.id(),time,claimId);
            // all other PENDING claims become HELD
            db.update("UPDATE claims SET status='HELD' WHERE found_notice_id=? AND id<>? AND status='PENDING'",c.foundId(),claimId);
            // found notice -> AWAITING_COLLECTION
            db.update("UPDATE notices SET status='AWAITING_COLLECTION',updated_at=?,version=version+1 WHERE id=?",time,c.foundId());
            // active reservation
            db.update("DELETE FROM active_reservations WHERE notice_id=?",c.foundId());
            db.update("INSERT INTO active_reservations(notice_id,claim_id) VALUES(?,?)",c.foundId(),claimId);
            // claim_guards seam
            db.update("UPDATE claim_guards SET status='APPROVED' WHERE found_id=? AND status IN ('PENDING','HELD')",c.foundId());
        } else {
            db.update("UPDATE claims SET status='REJECTED',decision_reason=?,reviewer_id=?,decided_at=? WHERE id=?",reason,actor.id(),time,claimId);
            db.update("UPDATE claim_guards SET status='REJECTED' WHERE found_id=? AND lost_id IS NOT NULL AND status='PENDING'",c.foundId());
            releaseIfNoActive(c.foundId());
        }
        return view(requireClaim(claimId,false),actor);
    }

    private void releaseIfNoActive(String foundId) {
        Long active=db.queryForObject("SELECT COUNT(*) FROM claims WHERE found_notice_id=? AND status IN ('PENDING','HELD','APPROVED')",Long.class,foundId);
        if(active==0) {
            db.update("UPDATE notices SET status='OPEN',updated_at=?,version=version+1 WHERE id=? AND status='CLAIM_UNDER_REVIEW'",now(),foundId);
        }
    }

    @Transactional
    public ClaimView confirmReturn(String claimId,Actor actor) {
        var c=requireClaim(claimId,true);
        var found=requireNotice(c.foundId(),true);
        if(!"APPROVED".equals(c.status())) throw ApiProblem.conflict("Only an approved claim can be confirmed for return.");
        if(c.disputed()) throw ApiProblem.conflict("A dispute has been raised. Moderation must resolve it before completion.");
        boolean isFinder=found.authorId().equals(actor.id());
        boolean isClaimant=c.claimantId().equals(actor.id());
        if(!isFinder&&!isClaimant) throw ApiProblem.forbidden();
        String time=now();
        if(isFinder) {
            if(c.finderConfirmed()) throw ApiProblem.conflict("You have already confirmed.");
            db.update("UPDATE claims SET finder_confirmed=TRUE WHERE id=?",claimId);
        } else {
            if(c.claimantConfirmed()) throw ApiProblem.conflict("You have already confirmed.");
            db.update("UPDATE claims SET claimant_confirmed=TRUE WHERE id=?",claimId);
        }
        // re-read
        c=requireClaim(claimId,true);
        if(c.finderConfirmed()&&c.claimantConfirmed()) {
            // both confirmed -> complete
            db.update("UPDATE claims SET status='RETURNED',completed_at=? WHERE id=?",time,claimId);
            db.update("UPDATE notices SET status='RETURNED',updated_at=?,version=version+1 WHERE id=?",time,c.foundId());
            if(c.lostId()!=null) {
                db.update("UPDATE notices SET status='CLOSED',closed_reason='Item returned via approved claim.',updated_at=?,version=version+1 WHERE id=?",time,c.lostId());
            }
            // all other claims on this item -> ITEM_RETURNED
            db.update("UPDATE claims SET status='ITEM_RETURNED' WHERE found_notice_id=? AND status IN ('HELD','PENDING')",c.foundId());
            db.update("DELETE FROM active_reservations WHERE notice_id=?",c.foundId());
        }
        return view(requireClaim(claimId,false),actor);
    }

    @Transactional
    public ClaimView dispute(String claimId,ClaimModels.Message in,Actor actor) {
        if(in==null||in.content()==null||in.content().isBlank()) throw ApiProblem.invalid(Map.of("content","Describe the dispute."));
        String reason=in.content().strip();
        if(len(reason)>1000) throw ApiProblem.invalid(Map.of("content","Use at most 1,000 characters."));
        var c=requireClaim(claimId,true);
        var found=requireNotice(c.foundId(),true);
        if(!"APPROVED".equals(c.status())) throw ApiProblem.conflict("You can only dispute an approved claim.");
        boolean isFinder=found.authorId().equals(actor.id());
        boolean isClaimant=c.claimantId().equals(actor.id());
        if(!isFinder&&!isClaimant) throw ApiProblem.forbidden();
        db.update("UPDATE claims SET disputed=TRUE,dispute_reason=? WHERE id=?",reason,claimId);
        return view(requireClaim(claimId,false),actor);
    }
}
