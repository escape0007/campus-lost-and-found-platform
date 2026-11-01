package edu.must.campuslostfound;

import java.security.Principal;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static edu.must.campuslostfound.NoticeModels.*;
import static edu.must.campuslostfound.ClaimModels.*;

@RestController
@RequestMapping("/api")
class ClaimController {
    private final ClaimService claims;
    private final AccountService accounts;
    ClaimController(ClaimService claims,AccountService accounts) { this.claims=claims; this.accounts=accounts; }
    private Actor actor(Principal user) {
        if(user==null) throw new ApiProblem(HttpStatus.UNAUTHORIZED,"SIGN_IN_REQUIRED","Please sign in to continue.");
        return accounts.actor(user.getName());
    }
    @PostMapping("/notices/{foundId}/claims")
    ResponseEntity<ClaimView> submit(@PathVariable String foundId,@RequestBody SubmitClaim body,Principal user) {
        var v=claims.submit(body,foundId,actor(user));
        return ResponseEntity.status(201).body(v);
    }
    @GetMapping("/notices/{id}/claims/mine") java.util.List<ClaimView> myClaimsForNotice(@PathVariable String id,Principal user) {
        return claims.listForNotice(id,actor(user));
    }
    @GetMapping("/claims/{id}") ClaimView get(@PathVariable String id,Principal user) { return claims.get(id,actor(user)); }
    @PostMapping("/claims/{id}/messages") ClaimView message(@PathVariable String id,@RequestBody Message body,Principal user) { return claims.sendMessage(id,body,actor(user)); }
    @PostMapping("/claims/{id}/withdraw") ClaimView withdraw(@PathVariable String id,Principal user) { return claims.withdraw(id,actor(user)); }
    @PostMapping("/claims/{id}/approve") ClaimView approve(@PathVariable String id,@RequestBody Decision body,Principal user) { return claims.approve(id,body,actor(user)); }
    @PostMapping("/claims/{id}/reject") ClaimView reject(@PathVariable String id,@RequestBody Decision body,Principal user) { return claims.reject(id,body,actor(user)); }
    @PostMapping("/claims/{id}/confirm-return") ClaimView confirm(@PathVariable String id,Principal user) { return claims.confirmReturn(id,actor(user)); }
    @PostMapping("/claims/{id}/dispute") ClaimView dispute(@PathVariable String id,@RequestBody Message body,Principal user) { return claims.dispute(id,body,actor(user)); }
}

@RestController
@RequestMapping("/api/moderation")
class ModerationController {
    private final ModerationService mod;
    private final AccountService accounts;
    ModerationController(ModerationService mod,AccountService accounts) { this.mod=mod; this.accounts=accounts; }
    private Actor actor(Principal user) {
        if(user==null) throw new ApiProblem(HttpStatus.UNAUTHORIZED,"SIGN_IN_REQUIRED","Please sign in to continue.");
        return accounts.actor(user.getName());
    }
    @GetMapping("/reports") java.util.List<ReportView> list(Principal user) { return mod.list(actor(user)); }
    @PostMapping("/reports/{id}/resolve") ReportView resolve(@PathVariable String id,@RequestBody ResolveReport body,Principal user) { return mod.resolve(id,body,actor(user)); }
}

@RestController
@RequestMapping("/api")
class ReportSubmitController {
    private final ModerationService mod;
    private final AccountService accounts;
    ReportSubmitController(ModerationService mod,AccountService accounts) { this.mod=mod; this.accounts=accounts; }
    private Actor actor(Principal user) {
        if(user==null) throw new ApiProblem(HttpStatus.UNAUTHORIZED,"SIGN_IN_REQUIRED","Please sign in to continue.");
        return accounts.actor(user.getName());
    }
    @PostMapping("/notices/{id}/report") ReportView reportNotice(@PathVariable String id,@RequestBody Report body,Principal user) { return mod.reportNotice(id,body,actor(user)); }
    @PostMapping("/claims/{id}/report-dispute") ReportView reportClaim(@PathVariable String id,@RequestBody Report body,Principal user) { return mod.reportClaimDispute(id,body,actor(user)); }
}
