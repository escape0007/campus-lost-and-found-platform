package edu.must.campuslostfound;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Frozen t12-2026-10-25 ordering. This is not the 20-case AT-05 protocol. */
class MatchRankerTest {
    @Test
    void ranksCategoryThenWordsThenAreaThenDateThenId() {
        MatchRanker.NoticeFacts lost = open("lost", "LOST", "蓝色保温瓶", "BOTTLE", "LIBRARY", "2026-10-20", false, "图书馆遗失蓝色保温瓶。");
        MatchRanker.NoticeFacts same = open("m", "FOUND", "蓝色保温瓶", "BOTTLE", "LIBRARY", "2026-10-20", false, "图书馆捡到蓝色保温瓶。");
        MatchRanker.NoticeFacts otherCategory = open("a", "FOUND", "蓝色保温瓶", "KEYS", "LIBRARY", "2026-10-20", false, "图书馆捡到蓝色保温瓶。");
        MatchRanker.NoticeFacts nearby = open("n", "FOUND", "蓝色保温瓶", "BOTTLE", "TEACHING_BUILDING", "2026-10-20", false, "图书馆捡到蓝色保温瓶。");
        MatchRanker.NoticeFacts week = open("w", "FOUND", "蓝色保温瓶", "BOTTLE", "LIBRARY", "2026-10-14", false, "图书馆捡到蓝色保温瓶。");
        MatchRanker.NoticeFacts older = open("o", "FOUND", "蓝色保温瓶", "BOTTLE", "LIBRARY", "2026-10-01", false, "图书馆捡到蓝色保温瓶。");
        MatchRanker.NoticeFacts tieLow = open("b", "FOUND", "无关物件", "BAG", "SPORTS_AREA", "2026-09-01", false, "运动场的一只空包。");
        MatchRanker.NoticeFacts tieHigh = open("c", "FOUND", "无关物件", "BAG", "SPORTS_AREA", "2026-09-01", false, "运动场的一只空包。");

        List<String> ids = MatchRanker.rank(lost, List.of(otherCategory, nearby, week, older, tieHigh, tieLow, same,
                open("self-type", "LOST", "蓝色保温瓶", "BOTTLE", "LIBRARY", "2026-10-20", false, "图书馆遗失蓝色保温瓶。"),
                closed("closed"), hidden("hidden"), reserved("reserved"))).stream().map(item -> item.candidate().id()).toList();

        assertThat(ids).startsWith("m", "w", "o", "n", "a");
        assertThat(ids).containsSubsequence("b", "c");
        assertThat(ids).doesNotContain("lost", "self-type", "closed", "hidden", "reserved");
    }

    @Test
    void missingDetailLowersConfidenceWithoutChangingTieOrder() {
        MatchRanker.NoticeFacts lost = open("lost", "LOST", "Blue Flask", "BOTTLE", "LIBRARY", "2026-10-20", false, "A blue flask left in the library.");
        MatchRanker.NoticeFacts exact = open("z-exact", "FOUND", "Blue Flask", "BOTTLE", "LIBRARY", "2026-10-20", false, "A blue flask left in the library.");
        MatchRanker.NoticeFacts approximate = open("a-approx", "FOUND", "Blue Flask", "BOTTLE", "LIBRARY", "2026-10-20", true, "A blue flask left in the library.");
        List<MatchRanker.Ranked> ranked = MatchRanker.rank(lost, List.of(exact, approximate));
        assertThat(ranked).extracting(item -> item.candidate().id()).containsExactly("a-approx", "z-exact");
        assertThat(ranked.get(0).confidence()).isLessThan(ranked.get(1).confidence());
        assertThat(ranked.get(0).reasons()).contains("An approximate date lowers confidence");
        assertThat(ranked.get(1).confidenceLabel()).isEqualTo("High");
        assertThat(ranked.get(1).reasons()).contains("Same category: Bottle", "Same area: Library", "Same day");
    }

    @Test
    void otherCategoryAndAreaAndDistantDateReduceConfidence() {
        MatchRanker.NoticeFacts lost = open("lost", "LOST", "蓝色", "OTHER", "OTHER", "2026-10-20", true, "只有很少的公开描述。");
        MatchRanker.Ranked ranked = MatchRanker.rank(lost, List.of(
                open("found", "FOUND", "完全不同", "KEYS", "SPORTS_AREA", "2026-09-01", false, "运动场钥匙一串。"))).getFirst();
        assertThat(ranked.confidence()).isLessThan(40);
        assertThat(ranked.confidenceLabel()).isEqualTo("Low");
        assertThat(ranked.reasons()).contains("An unspecified category lowers confidence", "An unspecified area lowers confidence");
    }

    @Test
    void repeatedRankingIsIdentical() {
        MatchRanker.NoticeFacts lost = open("lost", "LOST", "Blue Flask", "BOTTLE", "LIBRARY", "2026-10-20", false, "A blue flask.");
        List<MatchRanker.NoticeFacts> found = List.of(
                open("b", "FOUND", "Blue Flask", "BOTTLE", "CANTEEN", "2026-10-18", false, "A blue flask."),
                open("a", "FOUND", "Blue Flask", "BOTTLE", "CANTEEN", "2026-10-18", false, "A blue flask."));
        assertThat(MatchRanker.rank(lost, found)).isEqualTo(MatchRanker.rank(lost, found));
        assertThat(MatchRanker.areaScore("LIBRARY", "TEACHING_BUILDING")).isEqualTo(1);
        assertThat(MatchRanker.areaScore("TEACHING_BUILDING", "LIBRARY")).isEqualTo(1);
        assertThat(MatchRanker.dateScore("2026-10-20", "2026-10-13")).isEqualTo(1);
        assertThat(MatchRanker.dateScore("2026-10-20", "2026-10-12")).isZero();
    }

    private static MatchRanker.NoticeFacts open(String id, String type, String title, String category, String area, String date, boolean approximate, String description) {
        return new MatchRanker.NoticeFacts(id, type, title, category, area, date, approximate, description, "OPEN", false, false, 1);
    }

    private static MatchRanker.NoticeFacts closed(String id) {
        return new MatchRanker.NoticeFacts(id, "FOUND", "Blue Flask", "BOTTLE", "LIBRARY", "2026-10-20", false, "A blue flask.", "CLOSED", false, false, 1);
    }

    private static MatchRanker.NoticeFacts hidden(String id) {
        return new MatchRanker.NoticeFacts(id, "FOUND", "Blue Flask", "BOTTLE", "LIBRARY", "2026-10-20", false, "A blue flask.", "OPEN", true, false, 1);
    }

    private static MatchRanker.NoticeFacts reserved(String id) {
        return new MatchRanker.NoticeFacts(id, "FOUND", "Blue Flask", "BOTTLE", "LIBRARY", "2026-10-20", false, "A blue flask.", "OPEN", false, true, 1);
    }
}
