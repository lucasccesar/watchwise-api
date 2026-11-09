package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageMetadataDTO;
import com.watchwise.watchwise_api.content.dto.SeasonSummaryDTO;

import java.util.List;

public record ContentPageDetails(
        ContentDetailsDTO details,
        List<SeasonSummaryDTO> siblingSeasons,
        EpisodeNavigationContext episodeNavigation,
        ContentPageMetadataDTO metadata) {

    public ContentPageDetails(ContentDetailsDTO details, List<SeasonSummaryDTO> siblingSeasons) {
        this(details, siblingSeasons, null, null);
    }

    public ContentPageDetails(
            ContentDetailsDTO details,
            List<SeasonSummaryDTO> siblingSeasons,
            EpisodeNavigationContext episodeNavigation) {
        this(details, siblingSeasons, episodeNavigation, null);
    }

    public ContentPageDetails {
        siblingSeasons = siblingSeasons == null ? List.of() : List.copyOf(siblingSeasons);
    }
}
