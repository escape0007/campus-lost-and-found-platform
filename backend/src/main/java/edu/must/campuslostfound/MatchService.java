package edu.must.campuslostfound;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static edu.must.campuslostfound.MatchModels.*;
import static edu.must.campuslostfound.MatchRanker.*;
import static edu.must.campuslostfound.NoticeModels.Actor;

@Service
public class MatchService {
    private static final String COLUMNS = "id,type,title,category,area,occurrence_date,approximate,description,status,hidden,material_version,"
            + "EXISTS (SELECT 1 FROM active_reservations a WHERE a.notice_id=notices.id) AS reserved";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;

    MatchService(JdbcTemplate db, ObjectMapper json, Clock clock) {
        this.db = db;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public SuggestionPage suggestions(String noticeId, Actor actor) {
        try {
            return rankCurrent(noticeId, actor);
        } catch (DataAccessException ex) {
            throw unavailable();
        }
    }

    @Transactional
    public void ignore(String noticeId, String otherId, Actor actor) {
        try {
            if (noticeId == null || otherId == null || noticeId.equals(otherId)) {
                throw ApiProblem.invalid(java.util.Map.of("suggestion", "Choose a different notice."));
            }
            requireVisible(noticeId);
            requireVisible(otherId);
            String pair = canonicalPair(noticeId, otherId);
            String when = clock.instant().toString();
            db.update("INSERT INTO ignored_pairs(user_id,canonical_pair_id,dismissed_at) "
                    + "SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM ignored_pairs WHERE user_id=? AND canonical_pair_id=?)",
                    actor.id(), pair, when, actor.id(), pair);
        } catch (DataAccessException ex) {
            throw unavailable();
        }
    }

    private SuggestionPage rankCurrent(String noticeId, Actor actor) {
        NoticeFacts source = requireVisible(noticeId);
        boolean eligible = "OPEN".equals(source.status()) && !source.reserved();
        if (!eligible) {
            return new SuggestionPage(CONFIG_VERSION, false, DISCLAIMER,
                    "Suggestions compare Open notices only. Returned, Closed, hidden, reserved and other unavailable notices stay out.",
                    List.of(), 0, false);
        }
        String opposite = "LOST".equals(source.type()) ? "FOUND" : "LOST";
        Set<String> ignored = new HashSet<>(db.queryForList("SELECT canonical_pair_id FROM ignored_pairs WHERE user_id=?", String.class, actor.id()));
        List<NoticeFacts> candidates = new ArrayList<>();
        for (NoticeFacts candidate : db.query("SELECT " + COLUMNS + " FROM notices WHERE hidden=FALSE AND status='OPEN' AND type=? AND id<>?",
                (rs, row) -> facts(rs), opposite, source.id())) {
            if (!ignored.contains(canonicalPair(source.id(), candidate.id()))) candidates.add(candidate);
        }
        List<Ranked> ranked = rank(source, candidates);
        List<Ranked> shown = ranked.size() > LIMIT ? ranked.subList(0, LIMIT) : ranked;
        String evaluatedAt = clock.instant().toString();
        List<Suggestion> items = new ArrayList<>();
        int rank = 1;
        for (Ranked item : shown) {
            remember(source, item, evaluatedAt);
            NoticeFacts candidate = item.candidate();
            items.add(new Suggestion(candidate.id(), candidate.title(), candidate.type(), candidate.category(), candidate.area(),
                    candidate.occurrenceDate(), candidate.approximate(), candidate.status(), rank++, item.confidence(),
                    item.confidenceLabel(), item.reasons(), candidate.materialVersion()));
        }
        String message = items.isEmpty()
                ? "No Open notice of the opposite type is available."
                : ranked.size() > LIMIT ? "Showing the first 20 suggestions. Order is category, words, area, date, then notice ID." : "";
        return new SuggestionPage(CONFIG_VERSION, true, DISCLAIMER, message, List.copyOf(items), ranked.size(), ranked.size() > LIMIT);
    }

    private void remember(NoticeFacts source, Ranked item, String evaluatedAt) {
        NoticeFacts candidate = item.candidate();
        boolean sourceLost = "LOST".equals(source.type());
        String lostId = sourceLost ? source.id() : candidate.id();
        String foundId = sourceLost ? candidate.id() : source.id();
        long lostVersion = sourceLost ? source.materialVersion() : candidate.materialVersion();
        long foundVersion = sourceLost ? candidate.materialVersion() : source.materialVersion();
        db.update("DELETE FROM match_candidates WHERE lost_id=? AND found_id=? AND lost_material_version=? AND found_material_version=?",
                lostId, foundId, lostVersion, foundVersion);
        db.update("INSERT INTO match_candidates(lost_id,found_id,lost_material_version,found_material_version,category_score,text_score,area_score,date_score,confidence,reasons_json,config_version,evaluated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                lostId, foundId, lostVersion, foundVersion, item.categoryScore(), item.textScore(), item.areaScore(), item.dateScore(),
                item.confidence(), reasons(item.reasons()), CONFIG_VERSION, evaluatedAt);
    }

    private String reasons(List<String> reasons) {
        try {
            return json.writeValueAsString(reasons);
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    private NoticeFacts requireVisible(String id) {
        List<NoticeFacts> rows = db.query("SELECT " + COLUMNS + " FROM notices WHERE id=?", (rs, row) -> facts(rs), id);
        if (rows.isEmpty() || rows.getFirst().hidden()) throw ApiProblem.missing();
        return rows.getFirst();
    }

    private NoticeFacts facts(ResultSet rs) throws SQLException {
        return new NoticeFacts(rs.getString("id"), rs.getString("type"), rs.getString("title"), rs.getString("category"),
                rs.getString("area"), rs.getDate("occurrence_date").toLocalDate().toString(), rs.getBoolean("approximate"),
                rs.getString("description"), rs.getString("status"), rs.getBoolean("hidden"), rs.getBoolean("reserved"),
                rs.getLong("material_version"));
    }

    private static ApiProblem unavailable() {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "MATCH_UNAVAILABLE",
                "Suggestions are temporarily unavailable. This notice and manual search are unchanged. You can retry.");
    }
}
