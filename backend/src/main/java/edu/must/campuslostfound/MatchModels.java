package edu.must.campuslostfound;

import java.util.List;

final class MatchModels {
    private MatchModels() {}

    record Suggestion(String noticeId, String title, String type, String category, String area, String occurrenceDate,
                      boolean approximate, String status, int rank, int confidence, String confidenceLabel,
                      List<String> reasons, long materialVersion) {}

    record SuggestionPage(String configVersion, boolean eligible, String disclaimer, String message,
                          List<Suggestion> items, int totalEligible, boolean limited) {}
}
