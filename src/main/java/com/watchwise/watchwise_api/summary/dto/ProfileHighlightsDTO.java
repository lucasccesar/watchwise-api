package com.watchwise.watchwise_api.summary.dto;

public record ProfileHighlightsDTO(
        ProfileRewatchHighlightDTO rewatch,
        ProfileLongestWatchDTO longestWatch
) {
}
