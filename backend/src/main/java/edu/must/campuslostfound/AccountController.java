package edu.must.campuslostfound;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class AccountController {
    private final AccountService accounts;

    AccountController(AccountService accounts) { this.accounts = accounts; }

    @GetMapping("/session")
    Map<String, Object> session(HttpServletRequest request, Principal user) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        var profile = user == null ? java.util.Optional.<AccountService.AccountView>empty() : accounts.profile(user.getName());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", profile.isPresent());
        body.put("username", profile.map(AccountService.AccountView::username).orElse(""));
        body.put("displayName", profile.map(AccountService.AccountView::displayName).orElse(""));
        body.put("role", profile.map(AccountService.AccountView::role).orElse(""));
        body.put("campusVerified", false);
        body.put("csrfToken", token.getToken());
        body.put("csrfHeaderName", token.getHeaderName());
        return body;
    }

    @PostMapping("/register")
    ResponseEntity<AccountService.AccountView> register(@RequestBody RegisterRequest request) {
        AccountService.AccountView created = accounts.register(request.username(), request.displayName(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/account/role")
    ResponseEntity<Map<String, Object>> denyRole(Principal user) {
        accounts.denySelfRoleChange(user == null ? "" : user.getName());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "code", "FORBIDDEN",
                "message", "This action is not permitted.",
                "fieldErrors", Map.of()));
    }

    record RegisterRequest(String username, String displayName, String password) {}
}
