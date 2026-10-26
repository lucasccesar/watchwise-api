package com.watchwise.watchwise_api.platformimport.model;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;

import java.time.Instant;
import java.util.List;

public record ConfirmedWatchScopeSnapshot(
        MediaReference target,
        String scheduleVersion,
        Instant validUntil,
        boolean releaseComplete,
        List<EpisodeCoordinate> episodes,
        boolean localCalendarMarkerPresent,
        long localCalendarScopeVersion
) {
    public ConfirmedWatchScopeSnapshot {
        if (target == null || scheduleVersion == null || scheduleVersion.isBlank() || validUntil == null) {
            throw new IllegalArgumentException("A confirmed watch scope snapshot requires its target and version");
        }
        episodes = episodes == null ? List.of() : List.copyOf(episodes);
        if (episodes.isEmpty()) {
            throw new IllegalArgumentException("A confirmed watch scope snapshot must contain episodes");
        }
    }
}
