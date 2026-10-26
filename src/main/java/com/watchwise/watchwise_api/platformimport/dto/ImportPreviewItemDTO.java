package com.watchwise.watchwise_api.platformimport.dto;

import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportDatePrecision;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ImportPreviewItemDTO(
        UUID itemId,
        long ordinal,
        ImportRecordKind kind,
        ImportDecision decision,
        String outcome,
        String issue,
        LocalDate watchedDate,
        Instant watchedAt,
        ImportDatePrecision datePrecision,
        JsonNode resolvedTarget,
        String itemTitle,
        Integer itemTitleLength,
        Boolean itemTitleTruncated,
        String itemDescription,
        Integer itemDescriptionLength,
        Boolean itemDescriptionTruncated,
        String reviewText,
        Integer reviewTextLength,
        Boolean reviewTextTruncated,
        ImportWatchScopePreviewDTO watchScopePreview
) {
    public ImportPreviewItemDTO(UUID itemId, ImportRecordKind kind, ImportDecision decision, String outcome) {
        this(itemId, 0, kind, decision, outcome, null, null, null, null, null,
                null, null, false, null, null, false, null, null, false, null);
    }
}
