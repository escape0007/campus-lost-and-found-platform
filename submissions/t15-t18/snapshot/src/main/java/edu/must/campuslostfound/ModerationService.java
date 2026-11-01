package edu.must.campuslostfound;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static edu.must.campuslostfound.NoticeModels.Actor;

@Service
public class ModerationService {
    private final JdbcTemplate db;

    public ModerationService(JdbcTemplate db) { this.db = db; }

    private static ApiProblem bad(String msg) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", msg);
    }

    private void requireModerator(Actor actor) {
        try {
            String role = db.queryForObject("SELECT role FROM accounts WHERE username=?", String.class, actor.id());
            if (!"MODERATOR".equals(role)) throw ApiProblem.forbidden();
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw ApiProblem.forbidden();
        }
    }

    @Transactional
    public void reportNotice(String noticeId, Actor reporter, ClaimModels.Report req) {
        Integer n = db.queryForObject("SELECT COUNT(*) FROM notices WHERE id=?", Integer.class, noticeId);
        if (n == null || n == 0) throw ApiProblem.missing();
        insert("NOTICE", noticeId, reporter.id(), req.reason());
    }

    @Transactional
    public void reportClaimDispute(String claimId, Actor reporter, ClaimModels.Report req) {
        Integer n = db.queryForObject("SELECT COUNT(*) FROM claims WHERE id=?", Integer.class, claimId);
        if (n == null || n == 0) throw ApiProblem.missing();
        insert("CLAIM_DISPUTE", claimId, reporter.id(), req.reason());
    }

    private void insert(String type, String targetId, String reporterId, String reason) {
        if (reason == null || reason.isBlank()) throw bad("Reason required.");
        if (reason.length() > 500) throw bad("Reason max 500 characters.");
        db.update("INSERT INTO reports (id,target_type,target_id,reporter_id,reason,status,created_at) VALUES (?,?,?,?,?,'OPEN',?)",
                UUID.randomUUID().toString(), type, targetId, reporterId, reason, Instant.now().toString());
    }

    public List<ClaimModels.ReportView> list(Actor actor) {
        requireModerator(actor);
        return db.query("SELECT * FROM reports ORDER BY created_at DESC",
                (rs, n) -> {
                    String tid = rs.getString("target_id");
                    String title = null;
                    try {
                        title = db.queryForObject("SELECT title FROM notices WHERE id=?", String.class, tid);
                    } catch (Exception ignored) {}
                    return new ClaimModels.ReportView(rs.getString("id"), rs.getString("target_type"), tid,
                            rs.getString("reporter_id"), rs.getString("reason"), rs.getString("status"),
                            rs.getString("resolution"), rs.getString("resolution_note"),
                            rs.getString("created_at"),
                            rs.getString("resolved_at"),
                            title);
                });
    }

    @Transactional
    public void resolve(String reportId, Actor actor, ClaimModels.ResolveReport req) {
        requireModerator(actor);
        String status = db.queryForObject("SELECT status FROM reports WHERE id=?", String.class, reportId);
        if (status == null) throw ApiProblem.missing();
        if (!List.of("HIDE_NOTICE", "CANCEL_APPROVAL", "DISMISS").contains(req.resolution()))
            throw bad("Invalid resolution.");
        db.update("UPDATE reports SET status='RESOLVED', resolution=?, resolution_note=?, resolved_at=? WHERE id=?",
                req.resolution(), req.note(), Instant.now().toString(), reportId);
        if ("HIDE_NOTICE".equals(req.resolution())) {
            String tid = db.queryForObject("SELECT target_id FROM reports WHERE id=?", String.class, reportId);
            db.update("UPDATE notices SET status='HIDDEN', version=version+1 WHERE id=?", tid);
        } else if ("CANCEL_APPROVAL".equals(req.resolution())) {
            String tid = db.queryForObject("SELECT target_id FROM reports WHERE id=?", String.class, reportId);
            db.update("UPDATE claims SET status='DISPUTED', decided_at=? WHERE id=?",
                    Instant.now().toString(), tid);
        }
    }
}
