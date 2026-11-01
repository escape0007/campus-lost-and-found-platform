package edu.must.campuslostfound;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Frozen suggestion rule {@code t12-2026-10-25}.
 * Sort key, highest first: same category, shared words, area proximity, date closeness.
 * Equal keys use the candidate notice ID ascending. Confidence is explained separately and
 * does not reorder the list. A result is not ownership proof.
 */
final class MatchRanker {
    static final String CONFIG_VERSION = "t12-2026-10-25";
    static final int LIMIT = 20;
    static final String DISCLAIMER = "A suggestion does not prove ownership.";

    private static final Pattern CHUNK = Pattern.compile("\\p{IsHan}+|[A-Za-z0-9]+");
    private static final Set<String> LIBRARY_NEAR = Set.of("TEACHING_BUILDING");
    private static final Set<String> TEACHING_NEAR = Set.of("LIBRARY", "CANTEEN");
    private static final Set<String> CANTEEN_NEAR = Set.of("TEACHING_BUILDING", "DORMITORY");
    private static final Set<String> DORMITORY_NEAR = Set.of("CANTEEN", "SPORTS_AREA");
    private static final Set<String> SPORTS_NEAR = Set.of("DORMITORY");

    private MatchRanker() {}

    record NoticeFacts(String id, String type, String title, String category, String area, String occurrenceDate,
                       boolean approximate, String description, String status, boolean hidden, boolean reserved,
                       long materialVersion) {}

    record Ranked(NoticeFacts candidate, int categoryScore, int textScore, int areaScore, int dateScore,
                  int confidence, String confidenceLabel, List<String> reasons) {}

    static String canonicalPair(String leftId, String rightId) {
        return leftId.compareTo(rightId) < 0 ? leftId + ":" + rightId : rightId + ":" + leftId;
    }

    static List<Ranked> rank(NoticeFacts source, List<NoticeFacts> candidates) {
        String opposite = opposite(source.type());
        List<Ranked> ranked = new ArrayList<>();
        for (NoticeFacts candidate : candidates) {
            if (candidate == null || candidate.id() == null || candidate.id().equals(source.id())) continue;
            if (candidate.hidden() || candidate.reserved() || !"OPEN".equals(candidate.status())) continue;
            if (opposite == null || !opposite.equals(candidate.type())) continue;
            ranked.add(score(source, candidate));
        }
        ranked.sort(Comparator.comparingInt(Ranked::categoryScore).reversed()
                .thenComparing(Comparator.comparingInt(Ranked::textScore).reversed())
                .thenComparing(Comparator.comparingInt(Ranked::areaScore).reversed())
                .thenComparing(Comparator.comparingInt(Ranked::dateScore).reversed())
                .thenComparing(rankedItem -> rankedItem.candidate().id()));
        return List.copyOf(ranked);
    }

    private static Ranked score(NoticeFacts source, NoticeFacts candidate) {
        int categoryScore = source.category().equals(candidate.category()) ? 1 : 0;
        int textScore = shared(tokens(source), tokens(candidate)).size();
        int areaScore = areaScore(source.area(), candidate.area());
        int dateScore = dateScore(source.occurrenceDate(), candidate.occurrenceDate());
        List<String> reasons = new ArrayList<>();
        int confidence = 100;
        if (categoryScore == 1) reasons.add("Same category: " + label(source.category()));
        else { confidence -= 30; reasons.add("A different category lowers confidence"); }
        if (textScore > 0) reasons.add("Shared words: " + words(source, candidate));
        else { confidence -= 20; reasons.add("No shared words lowers confidence"); }
        if (areaScore == 2) reasons.add("Same area: " + label(source.area()));
        else if (areaScore == 1) reasons.add("Nearby areas: " + label(source.area()) + " and " + label(candidate.area()));
        else { confidence -= 15; reasons.add("A distant area lowers confidence"); }
        if (dateScore == 3) reasons.add("Same day");
        else if (dateScore == 2) reasons.add("Dates within 3 days");
        else if (dateScore == 1) reasons.add("Dates within 7 days");
        else { confidence -= 15; reasons.add("Dates more than 7 days apart lower confidence"); }
        if (source.approximate() || candidate.approximate()) {
            confidence -= 10; reasons.add("An approximate date lowers confidence");
        }
        if ("OTHER".equals(source.category()) || "OTHER".equals(candidate.category())) {
            confidence -= 10; reasons.add("An unspecified category lowers confidence");
        }
        if ("OTHER".equals(source.area()) || "OTHER".equals(candidate.area())) {
            confidence -= 10; reasons.add("An unspecified area lowers confidence");
        }
        confidence = Math.max(0, confidence);
        String label = confidence >= 70 ? "High" : confidence >= 40 ? "Medium" : "Low";
        return new Ranked(candidate, categoryScore, textScore, areaScore, dateScore, confidence, label, List.copyOf(reasons));
    }

    private static String opposite(String type) {
        if ("LOST".equals(type)) return "FOUND";
        if ("FOUND".equals(type)) return "LOST";
        return null;
    }

    static Set<String> tokens(NoticeFacts notice) {
        String text = (notice.title() == null ? "" : notice.title()) + "\n" + (notice.description() == null ? "" : notice.description());
        TreeSet<String> tokens = new TreeSet<>();
        Matcher matcher = CHUNK.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String chunk = matcher.group();
            if (chunk.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                int[] points = chunk.codePoints().toArray();
                if (points.length == 1) tokens.add(new String(points, 0, 1));
                else for (int i = 0; i < points.length - 1; i++) tokens.add(new String(points, i, 2));
            } else if (chunk.length() >= 2) tokens.add(chunk);
        }
        return tokens;
    }

    private static TreeSet<String> shared(Set<String> left, Set<String> right) {
        TreeSet<String> both = new TreeSet<>(left);
        both.retainAll(right);
        return both;
    }

    private static String words(NoticeFacts source, NoticeFacts candidate) {
        List<String> both = new ArrayList<>(shared(tokens(source), tokens(candidate)));
        int shown = Math.min(5, both.size());
        String text = String.join(", ", both.subList(0, shown));
        return both.size() > shown ? text + "…" : text;
    }

    static int areaScore(String left, String right) {
        if (left == null || right == null) return 0;
        if (left.equals(right)) return 2;
        return near(left).contains(right) ? 1 : 0;
    }

    private static Set<String> near(String area) {
        return switch (area) {
            case "LIBRARY" -> LIBRARY_NEAR;
            case "TEACHING_BUILDING" -> TEACHING_NEAR;
            case "CANTEEN" -> CANTEEN_NEAR;
            case "DORMITORY" -> DORMITORY_NEAR;
            case "SPORTS_AREA" -> SPORTS_NEAR;
            default -> Set.of();
        };
    }

    static int dateScore(String left, String right) {
        try {
            long days = Math.abs(ChronoUnit.DAYS.between(LocalDate.parse(left), LocalDate.parse(right)));
            if (days == 0) return 3;
            if (days <= 3) return 2;
            if (days <= 7) return 1;
            return 0;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    static String label(String value) {
        return switch (value) {
            case "KEYS" -> "Keys";
            case "UMBRELLA" -> "Umbrella";
            case "BOTTLE" -> "Bottle";
            case "BAG" -> "Bag";
            case "ELECTRONICS" -> "Electronics";
            case "OTHER" -> "Other";
            case "LIBRARY" -> "Library";
            case "TEACHING_BUILDING" -> "Teaching building";
            case "CANTEEN" -> "Canteen";
            case "DORMITORY" -> "Dormitory";
            case "SPORTS_AREA" -> "Sports area";
            default -> value;
        };
    }
}
