package edu.must.campuslostfound;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static edu.must.campuslostfound.AlertModels.*;
import static edu.must.campuslostfound.MatchRanker.NoticeFacts;
import static edu.must.campuslostfound.MatchRanker.Ranked;
import static edu.must.campuslostfound.MatchRanker.canonicalPair;
import static edu.must.campuslostfound.MatchRanker.rank;
import static edu.must.campuslostfound.NoticeModels.Actor;

/**
 * In-site new-match alerts. {@code t13-2026-10-25} is an unconfirmed proposal:
 * the suggested default is New-match alerts, and only High confidence (at least 70)
 * creates an alert. Ranking itself stays on {@code t12-2026-10-25}.
 * Eligibility uses the stored trigger order, never the time a delayed retry runs.
 */
@Service
public class AlertService {
    static final String CONFIG_VERSION = "t13-2026-10-25";
    static final int ALERT_MIN_CONFIDENCE = 70;
    static final int LIMIT = 50;
    static final String DISCLAIMER = "An alert does not prove ownership.";
    private static final String DEFAULT_NOTE = "New-match alerts is the proposed default. Team7 has not confirmed it.";
    private static final String MANUAL_NOTE = "Manual checking creates no new alerts. Search, suggestions and existing alerts stay available.";
    private static final String COLUMNS = "id,type,title,category,area,occurrence_date,approximate,description,status,hidden,material_version,"
            + "EXISTS (SELECT 1 FROM active_reservations a WHERE a.notice_id=notices.id) AS reserved";

    private final JdbcTemplate db;
    private final Clock clock;
    private final TransactionTemplate transactions;

    AlertService(JdbcTemplate db, Clock clock, PlatformTransactionManager manager) {
        this.db = db;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public PreferenceView readPreference(Actor actor) {
        try {
            return view(preference(actor.id(), false));
        } catch (DataAccessException ex) {
            throw unavailable();
        }
    }

    @Transactional
    public PreferenceView update(Actor actor, String mode, Long version) {
        if (!"NEW_MATCH_ALERTS".equals(mode) && !"MANUAL_CHECKING".equals(mode)) {
            throw ApiProblem.invalid(java.util.Map.of("mode", "请选择新匹配提醒或自行查看。"));
        }
        if (version == null) throw ApiProblem.invalid(java.util.Map.of("version", "请先刷新提醒方式再保存。"));
        Pref current = preference(actor.id(), true);
        if (current.version() != version) throw ApiProblem.conflict("提醒方式已更新，请刷新后再保存。");
        String enabledAt = current.enabledAt();
        long enableOrder = current.enableOrder();
        if ("NEW_MATCH_ALERTS".equals(mode) && !"NEW_MATCH_ALERTS".equals(current.mode())) {
            enableOrder = db.queryForObject("SELECT counter FROM commit_order WHERE id=1 FOR UPDATE", Long.class);
            enabledAt = clock.instant().toString();
        }
        int updated = db.update("UPDATE match_preferences SET mode=?,latest_enabled_at=?,latest_enable_order=?,version=version+1,proposed_default=FALSE WHERE user_id=? AND version=?",
                mode, enabledAt, enableOrder, actor.id(), version);
        if (updated != 1) throw ApiProblem.conflict("提醒方式已更新，请刷新后再保存。");
        return view(preference(actor.id(), false));
    }

    public AlertPage list(Actor actor) {
        deliverQuietly();
        try {
            Pref pref = preference(actor.id(), false);
            List<AlertItem> items = db.query("SELECT a.id,a.lost_id,a.found_id,a.lost_material_version,a.found_material_version,a.reason,a.confidence,a.trigger_order,a.created_at,a.read_at,"
                            + "lost.title AS lost_title,lost.status AS lost_status,lost.hidden AS lost_hidden,"
                            + "found.title AS found_title,found.status AS found_status,found.hidden AS found_hidden "
                            + "FROM match_alerts a JOIN notices lost ON lost.id=a.lost_id JOIN notices found ON found.id=a.found_id "
                            + "WHERE a.recipient_id=? ORDER BY a.created_at DESC, a.id DESC LIMIT 51",
                    (rs, row) -> item(rs), actor.id());
            boolean limited = items.size() > LIMIT;
            if (limited) items = new ArrayList<>(items.subList(0, LIMIT));
            String message = "MANUAL_CHECKING".equals(pref.mode())
                    ? "当前为自行查看。"
                    : items.isEmpty() ? "还没有提醒。" : "";
            if (limited) message = ("只显示最近 50 条。" + message).strip();
            return new AlertPage(CONFIG_VERSION, pref.mode(), DISCLAIMER, message, List.copyOf(items), limited);
        } catch (DataAccessException ex) {
            throw unavailable();
        }
    }

    @Transactional
    public void markRead(String id, Actor actor) {
        int updated = db.update("UPDATE match_alerts SET read_at=? WHERE id=? AND recipient_id=? AND read_at IS NULL",
                clock.instant().toString(), id, actor.id());
        if (updated == 1) return;
        Long own = db.queryForObject("SELECT COUNT(*) FROM match_alerts WHERE id=? AND recipient_id=?", Long.class, id, actor.id());
        if (own != null && own > 0) return;
        throw new ApiProblem(HttpStatus.NOT_FOUND, "UNAVAILABLE", "这条提醒不存在。");
    }

    public void deliverQuietly() {
        try {
            List<String> pending = db.queryForList("SELECT e.id FROM notice_events e WHERE NOT EXISTS "
                    + "(SELECT 1 FROM alert_event_deliveries d WHERE d.event_id=e.id) ORDER BY e.trigger_order LIMIT 200", String.class);
            for (String eventId : pending) {
                try {
                    transactions.executeWithoutResult(status -> evaluate(eventId));
                } catch (RuntimeException ignored) {
                    // Leave this event pending. The notice transaction has already committed.
                }
            }
        } catch (RuntimeException ignored) {
            // A later alert read retries pending events.
        }
    }

    /** Re-evaluates one stored event. A delayed call still uses that event's trigger order. */
    public void replay(String eventId) {
        transactions.executeWithoutResult(status -> evaluate(eventId));
    }

    private void evaluate(String eventId) {
        if (eventId == null || eventId.isBlank()) return;
        Event event = loadEvent(eventId);
        if (event == null) return;
        if (!"PUBLISHED".equals(event.kind()) && !"MATERIAL_CHANGED".equals(event.kind())) {
            markDelivered(event.id());
            return;
        }
        NoticeRow source = loadNotice(event.noticeId());
        if (source == null || !openForAlert(source) || source.facts().materialVersion() != event.materialVersion()) {
            markDelivered(event.id());
            return;
        }
        String opposite = "LOST".equals(source.facts().type()) ? "FOUND" : "FOUND".equals(source.facts().type()) ? "LOST" : null;
        if (opposite == null) {
            markDelivered(event.id());
            return;
        }
        List<NoticeFacts> comparable = new ArrayList<>();
        List<NoticeRow> candidates = new ArrayList<>();
        for (NoticeRow candidate : db.query("SELECT author_id," + COLUMNS + " FROM notices WHERE hidden=FALSE AND status='OPEN' AND type=? AND id<>?",
                (rs, row) -> notice(rs), opposite, source.facts().id())) {
            Long versionAtEvent = materialAt(candidate.facts().id(), event.triggerOrder());
            if (versionAtEvent == null || versionAtEvent != candidate.facts().materialVersion()) continue;
            if (!publishedBy(candidate.facts().id(), event.triggerOrder())) continue;
            if (!openForAlert(candidate)) continue;
            candidates.add(candidate);
            comparable.add(candidate.facts());
        }
        for (Ranked item : rank(source.facts(), comparable)) {
            if (item.confidence() < ALERT_MIN_CONFIDENCE) continue;
            NoticeRow candidate = candidates.stream().filter(row -> row.facts().id().equals(item.candidate().id())).findFirst().orElse(null);
            if (candidate == null) continue;
            boolean sourceLost = "LOST".equals(source.facts().type());
            String lostId = sourceLost ? source.facts().id() : candidate.facts().id();
            String foundId = sourceLost ? candidate.facts().id() : source.facts().id();
            long lostVersion = sourceLost ? event.materialVersion() : candidate.facts().materialVersion();
            long foundVersion = sourceLost ? candidate.facts().materialVersion() : event.materialVersion();
            Set<String> recipients = new LinkedHashSet<>();
            recipients.add(source.authorId());
            recipients.add(candidate.authorId());
            for (String recipient : recipients) {
                Pref pref = preference(recipient, true);
                if (!"NEW_MATCH_ALERTS".equals(pref.mode())) continue;
                if (event.triggerOrder() <= pref.enableOrder()) continue;
                if (ignored(recipient, lostId, foundId)) continue;
                insertAlert(recipient, lostId, foundId, lostVersion, foundVersion, reason(item), item.confidence(), event.triggerOrder());
            }
        }
        markDelivered(event.id());
    }

    private void insertAlert(String recipient, String lostId, String foundId, long lostVersion, long foundVersion,
                             String reason, int confidence, long triggerOrder) {
        db.update("INSERT INTO match_alerts(id,recipient_id,lost_id,found_id,lost_material_version,found_material_version,reason,confidence,trigger_order,created_at,config_version) "
                        + "SELECT ?,?,?,?,?,?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM match_alerts WHERE recipient_id=? AND lost_id=? AND found_id=? AND lost_material_version=? AND found_material_version=?)",
                UUID.randomUUID().toString(), recipient, lostId, foundId, lostVersion, foundVersion, reason, confidence, triggerOrder, clock.instant().toString(), CONFIG_VERSION,
                recipient, lostId, foundId, lostVersion, foundVersion);
    }

    private void markDelivered(String eventId) {
        db.update("INSERT INTO alert_event_deliveries(event_id,state,attempted_at) SELECT ?,'DELIVERED',? "
                        + "WHERE NOT EXISTS (SELECT 1 FROM alert_event_deliveries WHERE event_id=?)",
                eventId, clock.instant().toString(), eventId);
    }

    private boolean ignored(String userId, String lostId, String foundId) {
        Long count = db.queryForObject("SELECT COUNT(*) FROM ignored_pairs WHERE user_id=? AND canonical_pair_id=?",
                Long.class, userId, canonicalPair(lostId, foundId));
        return count != null && count > 0;
    }

    private boolean publishedBy(String noticeId, long triggerOrder) {
        Long count = db.queryForObject("SELECT COUNT(*) FROM notice_events WHERE notice_id=? AND kind='PUBLISHED' AND trigger_order<=?",
                Long.class, noticeId, triggerOrder);
        return count != null && count > 0;
    }

    private Long materialAt(String noticeId, long triggerOrder) {
        List<Long> rows = db.query("SELECT material_version FROM notice_events WHERE notice_id=? AND trigger_order<=? ORDER BY trigger_order DESC LIMIT 1",
                (rs, row) -> rs.getLong(1), noticeId, triggerOrder);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean openForAlert(NoticeRow row) {
        return "OPEN".equals(row.facts().status()) && !row.facts().hidden() && !row.facts().reserved();
    }

    private Pref preference(String userId, boolean lock) {
        String sql = "SELECT mode,latest_enabled_at,latest_enable_order,version,proposed_default FROM match_preferences WHERE user_id=?"
                + (lock ? " FOR UPDATE" : "");
        List<Pref> rows = db.query(sql, (rs, row) -> pref(rs), userId);
        if (!rows.isEmpty()) return rows.getFirst();
        long current = db.queryForObject("SELECT counter FROM commit_order WHERE id=1 FOR UPDATE", Long.class);
        db.update("INSERT INTO match_preferences(user_id,mode,latest_enabled_at,latest_enable_order,version,proposed_default) "
                        + "SELECT ?,?,?,?,1,TRUE WHERE NOT EXISTS (SELECT 1 FROM match_preferences WHERE user_id=?)",
                userId, "NEW_MATCH_ALERTS", clock.instant().toString(), current, userId);
        rows = db.query(sql, (rs, row) -> pref(rs), userId);
        if (rows.isEmpty()) throw new IllegalStateException("Preference was not saved");
        return rows.getFirst();
    }

    private PreferenceView view(Pref pref) {
        return new PreferenceView(pref.mode(), pref.version(), pref.proposed(), "PENDING", DEFAULT_NOTE, MANUAL_NOTE);
    }

    private Pref pref(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Pref(rs.getString("mode"), rs.getString("latest_enabled_at"), rs.getLong("latest_enable_order"),
                rs.getLong("version"), rs.getBoolean("proposed_default"));
    }

    private Event loadEvent(String eventId) {
        List<Event> rows = db.query("SELECT id,notice_id,kind,material_version,trigger_order FROM notice_events WHERE id=?",
                (rs, row) -> new Event(rs.getString("id"), rs.getString("notice_id"), rs.getString("kind"),
                        rs.getLong("material_version"), rs.getLong("trigger_order")), eventId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private NoticeRow loadNotice(String id) {
        List<NoticeRow> rows = db.query("SELECT author_id," + COLUMNS + " FROM notices WHERE id=?", (rs, row) -> notice(rs), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private NoticeRow notice(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new NoticeRow(rs.getString("author_id"), facts(rs));
    }

    private NoticeFacts facts(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new NoticeFacts(rs.getString("id"), rs.getString("type"), rs.getString("title"), rs.getString("category"),
                rs.getString("area"), rs.getDate("occurrence_date").toLocalDate().toString(), rs.getBoolean("approximate"),
                rs.getString("description"), rs.getString("status"), rs.getBoolean("hidden"), rs.getBoolean("reserved"),
                rs.getLong("material_version"));
    }

    private AlertItem item(java.sql.ResultSet rs) throws java.sql.SQLException {
        boolean lostHidden = rs.getBoolean("lost_hidden");
        boolean foundHidden = rs.getBoolean("found_hidden");
        int confidence = rs.getInt("confidence");
        String label = confidence >= 70 ? "High" : confidence >= 40 ? "Medium" : "Low";
        return new AlertItem(rs.getString("id"), rs.getString("lost_id"), rs.getString("found_id"),
                lostHidden ? "" : rs.getString("lost_title"), foundHidden ? "" : rs.getString("found_title"),
                rs.getString("lost_status"), rs.getString("found_status"), lostHidden, foundHidden,
                rs.getLong("lost_material_version"), rs.getLong("found_material_version"), rs.getString("reason"),
                confidence, label, rs.getLong("trigger_order"), rs.getString("created_at"), rs.getString("read_at") != null);
    }

    private String reason(Ranked item) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (item.categoryScore() == 1) parts.add("相同类别：" + zh(item.candidate().category()));
        else parts.add("类别不同");
        if (item.textScore() > 0) parts.add("有共同词语");
        else parts.add("没有共同词语");
        if (item.areaScore() == 2) parts.add("相同地区：" + zh(item.candidate().area()));
        else if (item.areaScore() == 1) parts.add("地区相近");
        else parts.add("地区较远");
        if (item.dateScore() == 3) parts.add("同一天");
        else if (item.dateScore() == 2) parts.add("日期在 3 天内");
        else if (item.dateScore() == 1) parts.add("日期在 7 天内");
        else parts.add("日期相隔超过 7 天");
        for (String entry : item.reasons()) {
            if (entry.startsWith("An approximate")) parts.add("日期为近似");
            else if (entry.startsWith("An unspecified category")) parts.add("类别未具体指定");
            else if (entry.startsWith("An unspecified area")) parts.add("地区未具体指定");
        }
        String level = "High".equals(item.confidenceLabel()) ? "高" : "Medium".equals(item.confidenceLabel()) ? "中" : "低";
        String text = level + "。" + String.join("；", parts);
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private static String zh(String value) {
        return switch (value) {
            case "KEYS" -> "钥匙";
            case "UMBRELLA" -> "雨伞";
            case "BOTTLE" -> "水瓶";
            case "BAG" -> "包袋";
            case "ELECTRONICS" -> "电子产品";
            case "LIBRARY" -> "图书馆";
            case "TEACHING_BUILDING" -> "教学楼";
            case "CANTEEN" -> "食堂";
            case "DORMITORY" -> "宿舍";
            case "SPORTS_AREA" -> "运动场";
            case "OTHER" -> "其他";
            default -> value == null ? "" : value;
        };
    }

    private static ApiProblem unavailable() {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "ALERTS_UNAVAILABLE",
                "提醒暂时不可用，可以稍后重试。");
    }

    private record Pref(String mode, String enabledAt, long enableOrder, long version, boolean proposed) {}
    private record Event(String id, String noticeId, String kind, long materialVersion, long triggerOrder) {}
    private record NoticeRow(String authorId, NoticeFacts facts) {}
}
