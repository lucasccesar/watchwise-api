package com.watchwise.watchwise_api.platformimport.dto;

import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;

import java.util.UUID;

public record ImportPreviewItemDTO(UUID itemId, ImportRecordKind kind, ImportDecision decision, String outcome) {
}
