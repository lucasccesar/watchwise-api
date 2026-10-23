package com.watchwise.watchwise_api.contentreview.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record HistoricalOpinion(
        MediaReference content,
        int watchNumber,
        String comment,
        Integer scoreAtPublication,
        Instant sourcePublishedAt,
        LocalDate sourcePublishedOn,
        String contextJson) {

    public HistoricalOpinion {
        Objects.requireNonNull(content, "content is required");
        if (watchNumber <= 0) {
            throw new IllegalArgumentException("watchNumber must be positive");
        }
        if (scoreAtPublication != null && (scoreAtPublication < 1 || scoreAtPublication > 10)) {
            throw new IllegalArgumentException("scoreAtPublication must be between 1 and 10");
        }
    }
}
