package com.watchwise.watchwise_api.contentreview.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record HistoricalViewingEvidence(
        MediaReference content,
        int watchNumber,
        String scopeHash,
        Set<UUID> eventIds) {

    public HistoricalViewingEvidence {
        Objects.requireNonNull(content, "content is required");
        if (watchNumber <= 0) {
            throw new IllegalArgumentException("watchNumber must be positive");
        }
        if (scopeHash == null || scopeHash.isBlank()) {
            throw new IllegalArgumentException("scopeHash is required");
        }
        Objects.requireNonNull(eventIds, "eventIds are required");
        if (eventIds.isEmpty() || eventIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("eventIds must contain at least one event");
        }
        eventIds = Set.copyOf(eventIds);
    }
}
