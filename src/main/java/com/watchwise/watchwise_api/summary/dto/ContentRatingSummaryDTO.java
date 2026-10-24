package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;

public record ContentRatingSummaryDTO(
        ContentRefDTO content,
        Integer score,
        Long version) {
}
