package com.watchwise.watchwise_api.diarycompletion.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;

import java.time.Instant;
import java.util.UUID;

public interface DiaryCompletionService {

    void refreshStaleScopes(UUID ownerId, Instant now);

    void invalidateScheduleScopes(UUID ownerId);

    default void refreshStaleSeasonScopes(UUID ownerId, Instant now) {
        refreshStaleScopes(ownerId, now);
    }

    default void refreshDiaryViewScopes(UUID ownerId, Instant now, boolean includeSeries) {
        if (includeSeries) {
            refreshStaleScopes(ownerId, now);
        } else {
            refreshStaleSeasonScopes(ownerId, now);
        }
    }

    boolean isComplete(UUID ownerId, MediaReference reference, int watchNumber);
}
