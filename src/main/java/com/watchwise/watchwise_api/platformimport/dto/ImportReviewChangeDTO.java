package com.watchwise.watchwise_api.platformimport.dto;

import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ReviewChangeType;

import java.util.List;
import java.util.UUID;

public record ImportReviewChangeDTO(
        UUID itemId,
        ReviewChangeType type,
        long revision,
        String displayTitle,
        ImportPreviewItemDTO item,
        List<ImportDecision> allowedOperations,
        List<ImportEditableFieldDTO> editableFields
) {
    public ImportReviewChangeDTO {
        allowedOperations = List.copyOf(allowedOperations);
        editableFields = List.copyOf(editableFields);
    }
}
