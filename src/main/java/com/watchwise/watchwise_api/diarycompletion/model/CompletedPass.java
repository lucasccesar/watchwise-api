package com.watchwise.watchwise_api.diarycompletion.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record CompletedPass(
        int watchNumber,
        UUID anchorDiaryEntryId,
        LocalDate completedOn,
        Instant orderedAt) {

    public CompletedPass {
        if (watchNumber <= 0) {
            throw new IllegalArgumentException("watchNumber must be positive");
        }
        Objects.requireNonNull(anchorDiaryEntryId, "anchorDiaryEntryId is required");
        Objects.requireNonNull(orderedAt, "orderedAt is required");
    }
}
