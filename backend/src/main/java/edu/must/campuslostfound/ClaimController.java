package edu.must.campuslostfound;

import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static edu.must.campuslostfound.ClaimModels.*;
import static edu.must.campuslostfound.NoticeModels.Actor;

@RestController
@RequestMapping("/api")
class ClaimController {
    private final ClaimService claims;
    private final ModerationService moderation;
    private final AccountService accounts;
    ClaimController(ClaimService claims,ModerationService moderation,AccountService accounts) {this.claims=claims;this.moderation=moderation;this.accounts=accounts;}
    private Actor actor(Principal user) {
        if(user==null) throw new ApiProblem(HttpStatus.UNAUTHORIZED,"SIGN_IN_REQUIRED","Please sign in to continue.");
        return accounts.actor(user.getName());
    }
    @PostMapping("/notices/{id}/claims")
    ResponseEntity<ClaimView> submit(@PathVariable String id,@RequestBody SubmitClaim input,@RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {
        ClaimView result=claims.submit(input,id,key,actor(user));
        return ResponseEntity.status(201).header("Location","/api/claims/"+result.id()).body(result);
    }
    @GetMapping("/claims") List<ClaimView> list(Principal user) {return claims.list(actor(user));}
    @GetMapping("/notices/{id}/claims/mine") List<ClaimView> noticeCases(@PathVariable String id,Principal user) {return claims.listForNotice(id,actor(user));}
    @GetMapping("/claims/{id}") ClaimView get(@PathVariable String id,Principal user) {return claims.get(id,actor(user));}
    @PostMapping("/claims/{id}/messages") ClaimView message(@PathVariable String id,@RequestBody Message input,@RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {return claims.sendMessage(id,input,key,actor(user));}
    @PostMapping("/claims/{id}/withdraw") ClaimView withdraw(@PathVariable String id,@RequestBody Mutation input,@RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {return claims.withdraw(id,input,key,actor(user));}
    @PostMapping("/claims/{id}/approve") ClaimView approve(@PathVariable String id,@RequestBody Decision input,@RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {return claims.decide(id,input,true,key,actor(user));}
    @PostMapping("/claims/{id}/reject") ClaimView reject(@PathVariable String id,@RequestBody Decision input,@RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {return claims.decide(id,input,false,key,actor(user));}
    @PostMapping("/claims/{id}/confirm-return") ClaimView confirm(@PathVariable String id,@RequestBody Mutation input,@RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {return claims.confirmReturn(id,input,key,actor(user));}
    @PostMapping("/claims/{id}/dispute") ClaimView dispute(@PathVariable String id,@RequestBody Message input,@RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {return claims.dispute(id,input,key,actor(user));}
    @PostMapping("/notices/{id}/report") ReportView reportNotice(@PathVariable String id,@RequestBody Report input,@RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {return moderation.reportNotice(id,input,key,actor(user));}
    @PostMapping("/claims/{id}/report-dispute") ReportView reportClaim(@PathVariable String id,@RequestBody Report input,@RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {return moderation.reportClaimDispute(id,input,key,actor(user));}
    @GetMapping("/moderation/reports") List<ReportView> reports(Principal user) {return moderation.list(actor(user));}
    @GetMapping("/moderation/reports/{id}") ReportView report(@PathVariable String id,Principal user) {return moderation.get(id,actor(user));}
    @PostMapping("/moderation/reports/{id}/assign") ReportView assign(@PathVariable String id,@RequestBody Assignment input,@RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {return moderation.assign(id,input,key,actor(user));}
    @PostMapping("/moderation/reports/{id}/release") ReportView release(@PathVariable String id,@RequestBody Assignment input,@RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {return moderation.release(id,input,key,actor(user));}
    @PostMapping("/moderation/reports/{id}/resolve") ReportView resolve(@PathVariable String id,@RequestBody ResolveReport input,@RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {return moderation.resolve(id,input,key,actor(user));}
}
