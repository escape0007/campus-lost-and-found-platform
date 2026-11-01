package edu.must.campuslostfound;

import java.util.List;

/** Private workflow DTOs. These are never embedded in public notice/search responses. */
final class ClaimModels {
    private ClaimModels() {}
    record SubmitClaim(String lostNoticeId, String evidence) {}
    record Message(String content, Long version) {}
    record Decision(String reason, Long version) {}
    record Mutation(Long version, Integer approvalRound) {}
    record Report(String reason) {}
    record Assignment(String reason) {}
    record ResolveReport(String resolution, String note, Long version) {}
    record CaseMsg(String id, String senderId, String senderName, String content, String createdAt,
                   String senderRole, long sequence) {}
    record CaseEvent(String action, String actorId, String previousState, String newState, String occurredAt) {}
    record ClaimView(String id, String foundNoticeId, String foundTitle, String claimantId, String claimantName,
                     String lostNoticeId, String lostTitle, String evidence, String status, String decisionReason,
                     String reviewerId, boolean finderConfirmed, boolean claimantConfirmed, boolean disputed,
                     String disputeReason, String createdAt, String decidedAt, String completedAt,
                     boolean canDecide, boolean canWithdraw, boolean canConfirm, List<CaseMsg> messages,
                     long version, int approvalRound, boolean canMessage, boolean canReportDispute,
                     boolean hidden, List<CaseEvent> history) {}
    record ReportView(String id, String targetType, String targetId, String reporterId, String reason, String status,
                      String resolution, String resolutionNote, String createdAt, String resolvedAt, String targetTitle,
                      long version, String assignedModeratorId, boolean canAssign, boolean canResolve) {}
}
