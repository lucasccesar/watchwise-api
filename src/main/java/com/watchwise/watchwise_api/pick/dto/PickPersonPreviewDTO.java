package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;

public record PickPersonPreviewDTO(
        String tmdbId,
        String name,
        String profileUrl,
        ContentPreviewStatus previewStatus) {}
