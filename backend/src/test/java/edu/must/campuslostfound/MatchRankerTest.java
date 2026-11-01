package edu.must.campuslostfound;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Frozen t14-2026-11-01 ordering. This is not the reserved 20-case AT-05 protocol. */
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
    void practiceCasesKeepStrongMatchesAndDropWeakLookalikes() {
        MatchRanker.NoticeFacts lost = practice("P-SIM-LOST", "LOST", "蓝色保温瓶", "BOTTLE", "LIBRARY", "2026-10-20", false,
                "图书馆三楼自习区遗失蓝色保温瓶，杯盖有白色划痕。");
        MatchRanker.NoticeFacts hit = practice("P-SIM-HIT", "FOUND", "蓝色保温瓶", "BOTTLE", "LIBRARY", "2026-10-20", false,
                "图书馆三楼捡到蓝色保温瓶，杯盖有白色划痕。");
        MatchRanker.NoticeFacts approx = practice("P-SIM-APPROX", "FOUND", "蓝色保温瓶", "BOTTLE", "LIBRARY", "2026-10-19", true,
                "图书馆三楼捡到蓝色保温瓶，杯盖有白色划痕。");
        MatchRanker.NoticeFacts near = practice("P-SIM-NEAR", "FOUND", "蓝色保温瓶", "BOTTLE", "TEACHING_BUILDING", "2026-10-20", false,
                "教学楼大厅捡到蓝色保温瓶，杯盖有白色划痕。");
        MatchRanker.NoticeFacts far = practice("P-SIM-FAR", "FOUND", "黑色保温瓶", "BOTTLE", "CANTEEN", "2026-10-18", false,
                "食堂一楼捡到黑色保温瓶，杯身没有明显划痕。");
        MatchRanker.NoticeFacts wrong = practice("P-SIM-WRONG", "FOUND", "蓝色保温瓶", "KEYS", "LIBRARY", "2026-10-20", false,
                "图书馆捡到蓝色保温瓶，但公告类别写成钥匙。");
        List<MatchRanker.Ranked> ranked = MatchRanker.rank(lost, List.of(wrong, far, near, approx, hit));

        assertThat(ranked).extracting(item -> item.candidate().id()).containsExactly(
                "P-SIM-HIT", "P-SIM-APPROX", "P-SIM-NEAR", "P-SIM-FAR", "P-SIM-WRONG");
        assertThat(confidence(ranked, "P-SIM-HIT")).isEqualTo(100);
        assertThat(confidence(ranked, "P-SIM-APPROX")).isEqualTo(90);
        assertThat(confidence(ranked, "P-SIM-NEAR")).isEqualTo(100);
        assertThat(confidence(ranked, "P-SIM-FAR")).isEqualTo(75);
        assertThat(confidence(ranked, "P-SIM-WRONG")).isEqualTo(65);
        assertThat(confidence(ranked, "P-SIM-HIT")).isGreaterThanOrEqualTo(MatchRanker.ALERT_MIN_CONFIDENCE);
        assertThat(confidence(ranked, "P-SIM-APPROX")).isGreaterThanOrEqualTo(MatchRanker.ALERT_MIN_CONFIDENCE);
        assertThat(confidence(ranked, "P-SIM-NEAR")).isGreaterThanOrEqualTo(MatchRanker.ALERT_MIN_CONFIDENCE);
        assertThat(confidence(ranked, "P-SIM-FAR")).isLessThan(MatchRanker.ALERT_MIN_CONFIDENCE);
        assertThat(confidence(ranked, "P-SIM-WRONG")).isLessThan(MatchRanker.ALERT_MIN_CONFIDENCE);
        assertThat(rejectedT13Confidence(ranked, "P-SIM-FAR", false, false)).isEqualTo(85);
        assertThat(rejectedT13Confidence(ranked, "P-SIM-WRONG", false, false)).isEqualTo(70);
    }

    @Test
    void missingDetailStaysBelowThePracticeThreshold() {
        MatchRanker.NoticeFacts bagLost = practice("P-MISS-BAG-LOST", "LOST", "灰色帆布袋", "BAG", "LIBRARY", "2026-10-20", false,
                "甲座旁落下灰色帆布袋，袋绳已经松开啦呀。");
        MatchRanker.NoticeFacts bagFound = practice("P-MISS-BAG-FOUND", "FOUND", "黑色塑料盒", "BAG", "LIBRARY", "2026-10-20", false,
                "乙柜内拾得黑色塑料盒，盒盖仍然盖得很严。");
        MatchRanker.Ranked bag = MatchRanker.rank(bagLost, List.of(bagFound)).getFirst();
        assertThat(bag.textScore()).isZero();
        assertThat(bag.confidence()).isEqualTo(75);
        assertThat(bag.confidenceLabel()).isEqualTo("Medium");
        assertThat(bag.confidence()).isLessThan(MatchRanker.ALERT_MIN_CONFIDENCE);
        assertThat(rejectedT13Confidence(List.of(bag), "P-MISS-BAG-FOUND", false, false)).isEqualTo(80);

        MatchRanker.NoticeFacts vagueLost = practice("P-MISS-VAGUE-LOST", "LOST", "遗失物品一件", "OTHER", "OTHER", "2026-10-20", true,
                "具体外观和牌子都没有写清，只能先登记遗失。");
        MatchRanker.NoticeFacts vagueFound = practice("P-MISS-VAGUE-FOUND", "FOUND", "捡到物品一件", "OTHER", "SPORTS_AREA", "2026-09-01", false,
                "现场没有留下品牌或颜色，描述暂时不足了。");
        MatchRanker.Ranked vague = MatchRanker.rank(vagueLost, List.of(vagueFound)).getFirst();
        assertThat(vague.confidence()).isEqualTo(20);
        assertThat(vague.confidenceLabel()).isEqualTo("Low");
        assertThat(vague.reasons()).contains(
                "An approximate date lowers confidence",
                "An unspecified category lowers confidence",
                "An unspecified area lowers confidence");
        assertThat(vague.confidence()).isLessThan(rejectedT13Confidence(List.of(vague), "P-MISS-VAGUE-FOUND", true, true));
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

    private static int confidence(List<MatchRanker.Ranked> ranked, String id) {
        return ranked.stream().filter(item -> item.candidate().id().equals(id)).findFirst().orElseThrow().confidence();
    }

    /** Rejected t13-2026-10-25 penalties. Kept only to show why the practice set did not keep that threshold. */
    private static int rejectedT13Confidence(List<MatchRanker.Ranked> ranked, String id, boolean approximate, boolean unspecified) {
        MatchRanker.Ranked item = ranked.stream().filter(row -> row.candidate().id().equals(id)).findFirst().orElseThrow();
        int confidence = 100;
        if (item.categoryScore() == 0) confidence -= 30;
        if (item.textScore() == 0) confidence -= 20;
        if (item.areaScore() == 0) confidence -= 15;
        if (item.dateScore() == 0) confidence -= 15;
        if (approximate) confidence -= 10;
        if (unspecified) confidence -= 20;
        return Math.max(0, confidence);
    }

    private static MatchRanker.NoticeFacts practice(String id, String type, String title, String category, String area, String date, boolean approximate, String description) {
        return open(id, type, title, category, area, date, approximate, description);
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
