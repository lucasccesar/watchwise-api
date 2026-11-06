package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.SeasonSummaryDTO;

import java.util.List;

public record ContentPageDetails(
        ContentDetailsDTO details,
        List<SeasonSummaryDTO> siblingSeasons) {

    public ContentPageDetails {
        siblingSeasons = siblingSeasons == null ? List.of() : List.copyOf(siblingSeasons);
    }
}
