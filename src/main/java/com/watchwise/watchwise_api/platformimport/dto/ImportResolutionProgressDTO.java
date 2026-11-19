package com.watchwise.watchwise_api.platformimport.dto;

import com.watchwise.watchwise_api.platformimport.model.ResolutionProgressPhase;

public record ImportResolutionProgressDTO(
        ResolutionProgressPhase phase,
        long examinedItems,
        long totalItems,
        long reviewRequiredCount,
        long reviewGeneration,
        long reviewRevision
) {
    public ImportResolutionProgressDTO {
        if (phase == null || examinedItems < 0 || totalItems < 0 || examinedItems > totalItems
                || reviewRequiredCount < 0 || reviewGeneration < 0 || reviewRevision < 0) {
            throw new IllegalArgumentException("Import resolution progress is inconsistent");
        }
    }
}
