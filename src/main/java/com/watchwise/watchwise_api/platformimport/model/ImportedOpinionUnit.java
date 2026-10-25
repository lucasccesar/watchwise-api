package com.watchwise.watchwise_api.platformimport.model;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinion;

public record ImportedOpinionUnit(
        String itemId,
        ImportSourceKey sourceKey,
        MediaReference content,
        HistoricalOpinion review,
        Integer initialScore) {

    public ImportedOpinionUnit {
        if (itemId == null || itemId.isBlank() || sourceKey == null || content == null) {
            throw new IllegalArgumentException("Imported opinion unit is incomplete");
        }
        if (initialScore != null && (initialScore < 1 || initialScore > 10)) {
            throw new IllegalArgumentException("Initial score must be between 1 and 10");
        }
    }
}
