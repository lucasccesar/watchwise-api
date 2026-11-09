package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.SeasonSummaryDTO;

import java.util.List;

public record ContentPageDetails(
        ContentDetailsDTO details,
        List<SeasonSummaryDTO> siblingSeasons,
        EpisodeNavigationContext episodeNavigation) {

    public ContentPageDetails(ContentDetailsDTO details, List<SeasonSummaryDTO> siblingSeasons) {
        this(details, siblingSeasons, null);
    }

    public ContentPageDetails {
        siblingSeasons = siblingSeasons == null ? List.of() : List.copyOf(siblingSeasons);
    }
}
