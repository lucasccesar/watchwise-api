package com.watchwise.watchwise_api.diarycompletion.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record EpisodeWatch(
        UUID diaryEntryId,
        EpisodeCoordinate episode,
        int watchNumber,
        LocalDate watchedDate,
        Instant recordedAt,
        Instant watchedAt) {

    public EpisodeWatch {
        Objects.requireNonNull(diaryEntryId, "diaryEntryId is required");
        Objects.requireNonNull(episode, "episode is required");
        Objects.requireNonNull(recordedAt, "recordedAt is required");
        if (watchNumber <= 0) {
            throw new IllegalArgumentException("watchNumber must be positive");
        }
    }
}
