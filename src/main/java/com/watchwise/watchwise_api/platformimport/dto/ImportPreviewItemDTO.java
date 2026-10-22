package com.watchwise.watchwise_api.platformimport.dto;

import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

public record ImportPreviewItemDTO(
        UUID itemId,
        long ordinal,
        ImportRecordKind kind,
        ImportDecision decision,
        String outcome,
        String issue,
        JsonNode resolvedTarget
) {
    public ImportPreviewItemDTO(UUID itemId, ImportRecordKind kind, ImportDecision decision, String outcome) {
        this(itemId, 0, kind, decision, outcome, null, null);
    }
}
