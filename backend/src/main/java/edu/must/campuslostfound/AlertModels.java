package edu.must.campuslostfound;

import java.util.List;

final class AlertModels {
    private AlertModels() {}

    record PreferenceUpdate(String mode, Long version) {}

    record PreferenceView(String mode, long version, boolean usingProposedDefault, String defaultConfirmation,
                          String defaultNote, String manualNote) {}

    record AlertItem(String id, String lostId, String foundId, String lostTitle, String foundTitle,
                     String lostStatus, String foundStatus, boolean lostHidden, boolean foundHidden,
                     long lostMaterialVersion, long foundMaterialVersion, String reason, int confidence,
                     String confidenceLabel, long triggerOrder, String createdAt, boolean read) {}

    record AlertPage(String configVersion, String mode, String disclaimer, String message,
                     List<AlertItem> items, boolean limited) {}
}
