package com.watchwise.watchwise_api.platformimport.dto;

import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;

import java.time.Instant;
import java.util.List;

public record ImportWatchScopePreviewDTO(
        boolean available,
        String issue,
        String scheduleVersion,
        Instant validUntil,
        Boolean releaseComplete,
        List<EpisodeCoordinate> episodes
) {
    public ImportWatchScopePreviewDTO {
        episodes = episodes == null ? List.of() : List.copyOf(episodes);
    }

    public static ImportWatchScopePreviewDTO unavailable(String issue) {
        return new ImportWatchScopePreviewDTO(false, issue, null, null, null, List.of());
    }
}
