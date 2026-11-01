package edu.must.campuslostfound;

import java.util.List;

final class ClaimModels {
    private ClaimModels() {}
    record SubmitClaim(String lostNoticeId, String evidence) {}
    record Message(String content) {}
    record Decision(String reason) {}
    record Report(String reason) {}
    record ResolveReport(String resolution, String note) {}
    record CaseMsg(String id,String senderId,String senderName,String content,String createdAt) {}
    record ClaimView(String id,String foundNoticeId,String foundTitle,String claimantId,String claimantName,
                     String lostNoticeId,String lostTitle,String evidence,String status,String decisionReason,
                     String reviewerId,boolean finderConfirmed,boolean claimantConfirmed,boolean disputed,
                     String disputeReason,String createdAt,String decidedAt,String completedAt,
                     boolean canDecide,boolean canWithdraw,boolean canConfirm,List<CaseMsg> messages) {}
    record ReportView(String id,String targetType,String targetId,String reporterId,String reason,String status,
                      String resolution,String resolutionNote,String createdAt,String resolvedAt,String targetTitle) {}
}
