package edu.must.campuslostfound;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static edu.must.campuslostfound.NoticeModels.Actor;

@RestController
class ClaimController {
    private final ClaimService claims;
    private final ModerationService moderation;

    ClaimController(ClaimService claims, ModerationService moderation) {
        this.claims = claims;
        this.moderation = moderation;
    }

    @PostMapping("/api/notices/{foundId}/claims")
    ClaimModels.ClaimView submit(@PathVariable String foundId, @AuthenticationPrincipal Actor actor, @RequestBody ClaimModels.SubmitClaim req) {
        return claims.submit(foundId, actor, req);
    }

    @GetMapping("/api/notices/{foundId}/claims/mine")
    List<ClaimModels.ClaimView> mine(@PathVariable String foundId, @AuthenticationPrincipal Actor actor) {
        return claims.mineFor(foundId, actor);
    }

    @GetMapping("/api/claims/{id}")
    ClaimModels.ClaimView get(@PathVariable String id, @AuthenticationPrincipal Actor actor) {
        return claims.get(id, actor);
    }

    @PostMapping("/api/claims/{id}/messages")
    void msg(@PathVariable String id, @AuthenticationPrincipal Actor actor, @RequestBody ClaimModels.Message req) {
        claims.sendMessage(id, actor, req);
    }

    @PostMapping("/api/claims/{id}/withdraw")
    void withdraw(@PathVariable String id, @AuthenticationPrincipal Actor actor) {
        claims.withdraw(id, actor);
    }

    @PostMapping("/api/claims/{id}/approve")
    void approve(@PathVariable String id, @AuthenticationPrincipal Actor actor, @RequestBody(required = false) ClaimModels.Decision req) {
        claims.decide(id, actor, true, req == null ? "approved" : req.reason());
    }

    @PostMapping("/api/claims/{id}/reject")
    void reject(@PathVariable String id, @AuthenticationPrincipal Actor actor, @RequestBody(required = false) ClaimModels.Decision req) {
        claims.decide(id, actor, false, req == null ? "" : req.reason());
    }

    @PostMapping("/api/claims/{id}/confirm-return")
    void confirm(@PathVariable String id, @AuthenticationPrincipal Actor actor) {
        claims.confirmReturn(id, actor);
    }

    @PostMapping("/api/claims/{id}/dispute")
    void dispute(@PathVariable String id, @AuthenticationPrincipal Actor actor, @RequestBody ClaimModels.Report req) {
        claims.dispute(id, actor, req.reason());
    }

    @PostMapping("/api/notices/{id}/report")
    ResponseEntity<Void> reportNotice(@PathVariable String id, @AuthenticationPrincipal Actor actor, @RequestBody ClaimModels.Report req) {
        moderation.reportNotice(id, actor, req);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @PostMapping("/api/claims/{id}/report-dispute")
    ResponseEntity<Void> reportDispute(@PathVariable String id, @AuthenticationPrincipal Actor actor, @RequestBody ClaimModels.Report req) {
        moderation.reportClaimDispute(id, actor, req);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}

@RestController
class ModerationController {
    private final ModerationService moderation;
    ModerationController(ModerationService m) { this.moderation = m; }

    @GetMapping("/api/moderation/reports")
    List<ClaimModels.ReportView> list(@AuthenticationPrincipal Actor actor) {
        return moderation.list(actor);
    }

    @PostMapping("/api/moderation/reports/{id}/resolve")
    void resolve(@PathVariable String id, @AuthenticationPrincipal Actor actor, @RequestBody ClaimModels.ResolveReport req) {
        moderation.resolve(id, actor, req);
    }
}
