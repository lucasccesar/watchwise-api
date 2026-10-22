package com.watchwise.watchwise_api.platformimport.dto;

import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;

import java.time.LocalDateTime;
import java.util.UUID;

public record ImportJobDTO(
        UUID id,
        ImportInputKind inputKind,
        ImportSourceFormat sourceFormat,
        ImportPlatform platform,
        ImportJobState state,
        long version,
        long previewVersion,
        String previewDigest,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime expiresAt
) {
}
