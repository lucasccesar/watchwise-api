package com.watchwise.watchwise_api.content.dto;

public record ContentPageDTO(
        ContentDetailsDTO details,
        ContentPageMetadataDTO metadata,
        ContentPageStatsDTO stats,
        long visibleReviewsCount,
        ContentViewerStateDTO viewerState,
        ContentNavigationDTO navigation,
        ContentPageSectionsDTO sections) {
}
