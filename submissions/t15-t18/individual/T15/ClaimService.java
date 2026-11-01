package edu.must.campuslostfound;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static edu.must.campuslostfound.NoticeModels.Actor;

@Service
public class ClaimService {
    private final JdbcTemplate db;

    public ClaimService(JdbcTemplate db) { this.db = db; }

    private static ApiProblem bad(String msg) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", msg);
    }

    record NoticeRow(String id, String authorId, String authorName, String type, String title,
                     String status, long version) {}

    private NoticeRow notice(String id, boolean lock) {
        String sql = "SELECT id,author_id,author_name,type,title,status,version FROM notices WHERE id=?" + (lock ? " FOR UPDATE" : "");
        List<NoticeRow> r = db.query(sql, (rs, n) -> new NoticeRow(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getLong(7)), id);
        if (r.isEmpty()) throw ApiProblem.missing();
        return r.get(0);
    }

    record ClaimRow(String id, String foundNoticeId, String finderId, String claimantId,
                    String lostNoticeId, String evidence, String status, String decisionReason,
                    String reviewerId, boolean finderConfirmed, boolean claimantConfirmed,
                    boolean disputed, String disputeReason, Instant createdAt,
                    Instant decidedAt, Instant completedAt) {
        boolean participant(Actor a) { return finderId.equals(a.id()) || claimantId.equals(a.id()); }
    }

    private ClaimRow claim(String id) {
        List<ClaimRow> r = db.query("SELECT * FROM claims WHERE id=?", (rs, n) -> new ClaimRow(
                rs.getString("id"), rs.getString("found_notice_id"), rs.getString("finder_id"),
                rs.getString("claimant_id"), rs.getString("lost_notice_id"), rs.getString("evidence"),
                rs.getString("status"), rs.getString("decision_reason"), rs.getString("reviewer_id"),
                rs.getBoolean("finder_confirmed"), rs.getBoolean("claimant_confirmed"),
                rs.getBoolean("disputed"), rs.getString("dispute_reason"),
                Instant.parse(rs.getString("created_at")),
                rs.getString("decided_at") == null ? null : Instant.parse(rs.getString("decided_at")),
                rs.getString("completed_at") == null ? null : Instant.parse(rs.getString("completed_at"))), id);
        if (r.isEmpty()) throw ApiProblem.missing();
        return r.get(0);
    }

    @Transactional
    public ClaimModels.ClaimView submit(String foundId, Actor actor, ClaimModels.SubmitClaim req) {
        if (req.evidence() == null || req.evidence().length() < 10 || req.evidence().length() > 1000)
            throw bad("Evidence must be 10-1000 characters.");
        NoticeRow found = notice(foundId, false);
        if (!"FOUND".equals(found.type())) throw bad("Only FOUND notices can be claimed.");
        if (!"OPEN".equals(found.status())) throw bad("Notice is not open.");
        if (found.authorId().equals(actor.id())) throw bad("You cannot claim your own found notice.");
        if (req.lostNoticeId() == null || req.lostNoticeId().isBlank()) throw bad("Link a LOST notice.");
        NoticeRow lost = notice(req.lostNoticeId(), false);
        if (!"LOST".equals(lost.type())) throw bad("Must link a LOST notice.");
        if (!lost.authorId().equals(actor.id())) throw bad("You can only link your own LOST notice.");
        Integer existing = db.queryForObject(
                "SELECT COUNT(*) FROM claims WHERE found_notice_id=? AND claimant_id=? AND status IN ('PENDING','HELD','APPROVED')",
                Integer.class, foundId, actor.id());
        if (existing != null && existing > 0) throw ApiProblem.conflict("You already have a claim on this notice.");
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO claims (id,found_notice_id,finder_id,claimant_id,lost_notice_id,evidence,status,created_at) VALUES (?,?,?,?,?,?,'PENDING',?)",
                id, foundId, found.authorId(), actor.id(), req.lostNoticeId(), req.evidence(), Instant.now().toString());
        return get(id, actor);
    }

    @Transactional
    public void sendMessage(String claimId, Actor actor, ClaimModels.Message req) {
        if (req.content() == null || req.content().isBlank() || req.content().length() > 1000)
            throw bad("Message must be 1-1000 characters.");
        ClaimRow c = claim(claimId);
        if (!c.participant(actor)) throw ApiProblem.forbidden();
        if ("RETURNED".equals(c.status()) || "REJECTED".equals(c.status()) || "WITHDRAWN".equals(c.status()))
            throw bad("This case is closed.");
        db.update("INSERT INTO case_messages (id,claim_id,sender_id,content,created_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID().toString(), claimId, actor.id(), req.content(), Instant.now().toString());
    }

    @Transactional
    public void withdraw(String claimId, Actor actor) {
        ClaimRow c = claim(claimId);
        if (!c.claimantId().equals(actor.id())) throw ApiProblem.forbidden();
        if (!"PENDING".equals(c.status()) && !"HELD".equals(c.status())) throw bad("Cannot withdraw now.");
        db.update("UPDATE claims SET status='WITHDRAWN', decided_at=? WHERE id=?",
                Instant.now().toString(), claimId);
    }

    @Transactional
    public void decide(String claimId, Actor reviewer, boolean approve, String reason) {
        ClaimRow c = claim(claimId);
        NoticeRow found = notice(c.foundNoticeId(), true); // FOR UPDATE lock
        if (!found.authorId().equals(reviewer.id())) throw ApiProblem.forbidden();
        if (!"PENDING".equals(c.status()) && !"HELD".equals(c.status())) throw bad("Already decided.");
        if (approve) {
            db.update("UPDATE claims SET status='APPROVED', decision_reason=?, reviewer_id=?, decided_at=? WHERE id=?",
                    reason, reviewer.id(), Instant.now().toString(), claimId);
            db.update("UPDATE claims SET status='HELD' WHERE found_notice_id=? AND id<>? AND status='PENDING'",
                    c.foundNoticeId(), claimId);
            db.update("UPDATE notices SET status='AWAITING_COLLECTION', version=version+1 WHERE id=?", c.foundNoticeId());
        } else {
            db.update("UPDATE claims SET status='REJECTED', decision_reason=?, reviewer_id=?, decided_at=? WHERE id=?",
                    reason, reviewer.id(), Instant.now().toString(), claimId);
        }
    }

    @Transactional
    public void confirmReturn(String claimId, Actor actor) {
        ClaimRow c = claim(claimId);
        if (!c.participant(actor)) throw ApiProblem.forbidden();
        if (!"APPROVED".equals(c.status())) throw bad("Case is not approved.");
        boolean isFinder = c.finderId().equals(actor.id());
        boolean isClaimant = c.claimantId().equals(actor.id());
        if (isFinder && c.finderConfirmed()) throw bad("You already confirmed.");
        if (isClaimant && c.claimantConfirmed()) throw bad("You already confirmed.");
        db.update("UPDATE claims SET finder_confirmed = CASE WHEN ?=? THEN TRUE ELSE finder_confirmed END, claimant_confirmed = CASE WHEN ?=? THEN TRUE ELSE claimant_confirmed END WHERE id=?",
                actor.id(), c.finderId(), actor.id(), c.claimantId(), claimId);
        ClaimRow fresh = claim(claimId);
        if (fresh.finderConfirmed() && fresh.claimantConfirmed()) {
            db.update("UPDATE claims SET status='RETURNED', completed_at=? WHERE id=?",
                    Instant.now().toString(), claimId);
            db.update("UPDATE notices SET status='RETURNED', version=version+1 WHERE id=?", c.foundNoticeId());
            db.update("UPDATE notices SET status='RETURNED', version=version+1 WHERE id=?", c.lostNoticeId());
            db.update("UPDATE claims SET status='ITEM_RETURNED' WHERE found_notice_id=? AND status='HELD'", c.foundNoticeId());
        }
    }

    @Transactional
    public void dispute(String claimId, Actor actor, String reason) {
        if (reason == null || reason.isBlank()) throw bad("Reason required.");
        ClaimRow c = claim(claimId);
        if (!c.participant(actor)) throw ApiProblem.forbidden();
        if (!"APPROVED".equals(c.status())) throw bad("Can only dispute an approved case.");
        db.update("UPDATE claims SET disputed=TRUE, dispute_reason=? WHERE id=?", reason, claimId);
    }

    public List<ClaimModels.ClaimView> mineFor(String foundId, Actor actor) {
        List<String> ids = db.query("SELECT id FROM claims WHERE found_notice_id=? AND claimant_id=? AND status<>'WITHDRAWN' ORDER BY created_at",
                (rs, n) -> rs.getString(1), foundId, actor.id());
        List<ClaimModels.ClaimView> out = new ArrayList<>();
        for (String id : ids) out.add(get(id, actor));
        return out;
    }

    public ClaimModels.ClaimView get(String claimId, Actor actor) {
        ClaimRow c = claim(claimId);
        if (!c.participant(actor)) throw ApiProblem.forbidden();
        NoticeRow found = notice(c.foundNoticeId(), false);
        NoticeRow lost = c.lostNoticeId() == null ? null : notice(c.lostNoticeId(), false);
        List<ClaimModels.CaseMsg> msgs = db.query(
                "SELECT id,sender_id,content,created_at FROM case_messages WHERE claim_id=? ORDER BY created_at",
                (rs, n) -> {
                    String sid = rs.getString("sender_id");
                    String sname = sid;
                    try { sname = db.queryForObject("SELECT display_name FROM accounts WHERE username=?", String.class, sid); } catch (Exception ignored) {}
                    return new ClaimModels.CaseMsg(rs.getString("id"), sid, sname, rs.getString("content"),
                            rs.getString("created_at"));
                }, c.id());
        boolean canDecide = found.authorId().equals(actor.id()) && ("PENDING".equals(c.status()) || "HELD".equals(c.status()));
        boolean canWithdraw = c.claimantId().equals(actor.id()) && ("PENDING".equals(c.status()) || "HELD".equals(c.status()));
        boolean canConfirm = "APPROVED".equals(c.status()) && c.participant(actor) && !c.disputed();
        return new ClaimModels.ClaimView(c.id, c.foundNoticeId(), found.title(), c.claimantId(),
                c.claimantId(), c.lostNoticeId(), lost == null ? null : lost.title(),
                c.evidence, c.status, c.decisionReason, c.reviewerId,
                c.finderConfirmed, c.claimantConfirmed, c.disputed, c.disputeReason,
                c.createdAt.toString(), c.decidedAt == null ? null : c.decidedAt.toString(),
                c.completedAt == null ? null : c.completedAt.toString(),
                canDecide, canWithdraw, canConfirm, msgs);
    }
}
