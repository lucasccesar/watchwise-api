package com.watchwise.watchwise_api.platformimport.dto;

import com.watchwise.watchwise_api.platformimport.model.ImportJobState;

import java.util.List;
import java.util.UUID;

public record ImportPreviewDTO(
        UUID importId,
        ImportJobState state,
        long previewVersion,
        String previewDigest,
        List<ImportPreviewItemDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public ImportPreviewDTO {
        items = List.copyOf(items);
    }
}
