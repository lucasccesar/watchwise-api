package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;

public record ContentWatchCountDTO(
        ContentRefDTO content,
        long count) {
}
