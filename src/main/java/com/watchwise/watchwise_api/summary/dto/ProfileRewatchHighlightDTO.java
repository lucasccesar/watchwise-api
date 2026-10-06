package com.watchwise.watchwise_api.summary.dto;

public record ProfileRewatchHighlightDTO(
        ProfileRewatchKind kind,
        ProfileHighlightContentDTO content,
        long count
) {
}
