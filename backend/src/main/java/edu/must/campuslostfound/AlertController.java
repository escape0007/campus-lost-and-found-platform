package edu.must.campuslostfound;

import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import static edu.must.campuslostfound.AlertModels.AlertPage;
import static edu.must.campuslostfound.AlertModels.PreferenceUpdate;
import static edu.must.campuslostfound.AlertModels.PreferenceView;
import static edu.must.campuslostfound.NoticeModels.Actor;

@RestController
@RequestMapping("/api")
class AlertController {
    private final AlertService alerts;
    private final AccountService accounts;

    AlertController(AlertService alerts, AccountService accounts) {
        this.alerts = alerts;
        this.accounts = accounts;
    }

    private Actor actor(Principal user) {
        if (user == null) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "SIGN_IN_REQUIRED", "Please sign in to continue.");
        return accounts.actor(user.getName());
    }

    @GetMapping("/alert-preference")
    PreferenceView preference(Principal user) { return alerts.readPreference(actor(user)); }

    @PutMapping("/alert-preference")
    PreferenceView save(@RequestBody(required = false) PreferenceUpdate update, Principal user) {
        if (update == null) throw ApiProblem.invalid(java.util.Map.of("mode", "请选择新匹配提醒或自行查看。"));
        return alerts.update(actor(user), update.mode(), update.version());
    }

    @GetMapping("/alerts")
    AlertPage list(Principal user) { return alerts.list(actor(user)); }

    @PostMapping("/alerts/{id}/read")
    ResponseEntity<Void> read(@PathVariable String id, Principal user) {
        alerts.markRead(id, actor(user));
        return ResponseEntity.noContent().build();
    }
}
