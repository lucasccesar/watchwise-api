package com.watchwise.watchwise_api.platformimport.dto;

import com.watchwise.watchwise_api.platformimport.model.ImportJobState;

import java.util.List;
import java.util.UUID;

public record ImportReviewPageDTO(
        UUID importId,
        ImportJobState state,
        long previewVersion,
        long reviewGeneration,
        long reviewRevision,
        List<ImportReviewChangeDTO> changes,
        String nextCursor,
        boolean hasMore
) {
    public ImportReviewPageDTO {
        changes = List.copyOf(changes);
    }
}
