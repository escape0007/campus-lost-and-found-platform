package edu.must.campuslostfound;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static edu.must.campuslostfound.NoticeModels.Actor;

@Service
public class AccountService {
    static final int BCRYPT_STRENGTH = 10;
    static final int FAILURE_LIMIT = 5;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);
    static final Duration LOCKOUT = Duration.ofMinutes(10);
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,32}$");
    private static final String PASSWORD_RULE = "Use 10–72 characters, at most 72 bytes. The password is stored without truncation.";

    private final JdbcTemplate db;
    private final Clock clock;
    private final PasswordEncoder encoder;
    private final String dummyHash;

    public AccountService(JdbcTemplate db, Clock clock, PasswordEncoder encoder) {
        this.db = db;
        this.clock = clock;
        this.encoder = encoder;
        // A fixed hash keeps unknown-user checks on the same password encoder as real accounts.
        this.dummyHash = encoder.encode("timing-pad-not-a-password");
    }

    public record AccountView(String username, String displayName, String role, boolean campusVerified) {}
    record Authenticated(String username, String displayName, String role) {}
    private record Row(String id, String username, String displayName, String passwordHash, String role, String status,
                        int failedAttempts, String windowStartedAt, String lockedUntil) {}

    @Transactional
    public AccountView register(String usernameRaw, String displayRaw, String passwordRaw) {
        String username = usernameRaw == null ? "" : usernameRaw.trim();
        String displayName = displayRaw == null ? "" : displayRaw.strip();
        String password = passwordRaw == null ? "" : passwordRaw;
        Map<String, String> errors = new LinkedHashMap<>();
        if (!USERNAME.matcher(username).matches()) errors.put("username", "Use 3–32 letters, digits or underscores.");
        int displayLength = displayName.codePointCount(0, displayName.length());
        if (displayLength < 2 || displayLength > 40 || displayName.codePoints().anyMatch(Character::isISOControl))
            errors.put("displayName", "Use 2–40 Unicode characters.");
        int passwordChars = password.codePointCount(0, password.length());
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (passwordChars < 10 || passwordChars > 72 || passwordBytes > 72) errors.put("password", PASSWORD_RULE);
        if (!errors.isEmpty()) throw ApiProblem.invalid(errors);

        String key = username.toLowerCase(Locale.ROOT);
        if (countByKey(key) > 0) throw ApiProblem.invalid(Map.of("username", "That username is already in use."));
        String hash;
        try {
            hash = encoder.encode(password);
        } catch (IllegalArgumentException ex) {
            throw ApiProblem.invalid(Map.of("password", PASSWORD_RULE));
        }
        try {
            // Role and campus verification are not client fields. New accounts are students and are not campus-verified.
            db.update("INSERT INTO accounts(id,username,username_key,display_name,password_hash,role,status,campus_verified,failed_attempts,created_at) VALUES(?,?,?,?,?,'STUDENT','ACTIVE',FALSE,0,?)",
                    UUID.randomUUID().toString(), username, key, displayName, hash, clock.instant().toString());
        } catch (DataIntegrityViolationException ex) {
            throw ApiProblem.invalid(Map.of("username", "That username is already in use."));
        }
        audit(username, "REGISTER", username, "SUCCESS");
        return new AccountView(username, displayName, "STUDENT", false);
    }

    /**
     * Checks the account lock, verifies the password, and records a failure for an existing account.
     * Every refusal looks the same to the caller: the result is empty, with no indication of whether the account exists.
     */
    @Transactional
    public Optional<Authenticated> authenticate(String usernameRaw, String passwordRaw) {
        String presented = usernameRaw == null ? "" : usernameRaw.trim();
        String password = passwordRaw == null ? "" : passwordRaw;
        if (!USERNAME.matcher(presented).matches()) {
            consume(password);
            audit("anonymous", "LOGIN", "unregistered", "DENIED");
            return Optional.empty();
        }
        List<Row> rows = db.query("SELECT id,username,display_name,password_hash,role,status,failed_attempts,window_started_at,locked_until FROM accounts WHERE username_key=? FOR UPDATE",
                this::row, presented.toLowerCase(Locale.ROOT));
        if (rows.isEmpty()) {
            consume(password);
            audit("anonymous", "LOGIN", "unregistered", "DENIED");
            return Optional.empty();
        }
        Row account = rows.getFirst();
        Instant now = clock.instant();
        int attempts = account.failedAttempts();
        Instant window = parse(account.windowStartedAt());
        Instant lockedUntil = parse(account.lockedUntil());
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            matches(password, account.passwordHash());
            audit(account.username(), "LOGIN", account.username(), "DENIED");
            return Optional.empty();
        }
        if (lockedUntil != null || (window != null && !now.isBefore(window.plus(FAILURE_WINDOW)))) {
            attempts = 0;
            window = null;
        }
        if (!"ACTIVE".equals(account.status()) || !matches(password, account.passwordHash())) {
            if (window == null) window = now;
            attempts++;
            Instant locked = attempts >= FAILURE_LIMIT ? now.plus(LOCKOUT) : null;
            saveCounters(account.id(), attempts, window, locked);
            audit(account.username(), "LOGIN", account.username(), "DENIED");
            return Optional.empty();
        }
        saveCounters(account.id(), 0, null, null);
        audit(account.username(), "LOGIN", account.username(), "SUCCESS");
        return Optional.of(new Authenticated(account.username(), account.displayName(), account.role()));
    }

    @Transactional(readOnly = true)
    public Optional<AccountView> profile(String username) {
        List<AccountView> rows = db.query("SELECT username,display_name,role FROM accounts WHERE username=?",
                (rs, n) -> new AccountView(rs.getString("username"), rs.getString("display_name"), rs.getString("role"), false), username);
        return rows.stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Actor actor(String username) {
        AccountView view = profile(username).orElseThrow(() -> new ApiProblem(HttpStatus.UNAUTHORIZED, "SIGN_IN_REQUIRED", "Please sign in to continue."));
        return new Actor(view.username(), view.displayName());
    }

    @Transactional
    public void denySelfRoleChange(String username) {
        String actor = username == null || username.isBlank() ? "anonymous" : username;
        audit(actor, "ROLE_CHANGE", actor, "DENIED");
    }

    @Transactional
    public void recordLogout(String username) {
        if (username == null || username.isBlank()) return;
        audit(username, "LOGOUT", username, "SUCCESS");
    }

    private long countByKey(String key) {
        Long count = db.queryForObject("SELECT COUNT(*) FROM accounts WHERE username_key=?", Long.class, key);
        return count == null ? 0 : count;
    }

    private void saveCounters(String id, int attempts, Instant window, Instant lockedUntil) {
        db.update("UPDATE accounts SET failed_attempts=?, window_started_at=?, locked_until=? WHERE id=?", ps -> {
            ps.setInt(1, attempts);
            if (window == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, window.toString());
            if (lockedUntil == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, lockedUntil.toString());
            ps.setString(4, id);
        });
    }

    private boolean matches(String password, String hash) {
        try {
            return encoder.matches(password, hash);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private void consume(String password) { matches(password, dummyHash); }

    private Instant parse(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getString("id"), rs.getString("username"), rs.getString("display_name"), rs.getString("password_hash"),
                rs.getString("role"), rs.getString("status"), rs.getInt("failed_attempts"), rs.getString("window_started_at"), rs.getString("locked_until"));
    }

    private void audit(String actor, String action, String target, String outcome) {
        db.update("INSERT INTO account_audit(id,actor_id,action,target,outcome,occurred_at) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(), actor, action, target, outcome, clock.instant().toString());
    }
}
