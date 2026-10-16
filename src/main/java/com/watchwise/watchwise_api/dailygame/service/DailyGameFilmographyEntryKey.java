package com.watchwise.watchwise_api.dailygame.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class DailyGameFilmographyEntryKey {

    private DailyGameFilmographyEntryKey() {
    }

    public static String forSnapshotEntry(UUID challengeId, int snapshotIndex) {
        if (challengeId == null) {
            throw new IllegalArgumentException("challengeId must not be null");
        }
        if (snapshotIndex < 0) {
            throw new IllegalArgumentException("snapshotIndex must not be negative");
        }
        String scopedRow = challengeId + ":filmography-entry:" + snapshotIndex;
        return UUID.nameUUIDFromBytes(scopedRow.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
