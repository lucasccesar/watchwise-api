package com.watchwise.watchwise_api.person.dto;

import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;

public record PersonPreviewDTO(
        String personTmdbId,
        String name,
        String profileUrl,
        ContentPreviewStatus previewStatus
) {
}
