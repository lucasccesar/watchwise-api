package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;

import java.util.Objects;

public record DiaryTimelineCompletionItemDTO(
        String kind,
        DiaryViewItemDTO completion,
        ContentCardDTO card,
        ContentCardDTO seriesCard) implements DiaryTimelineResponseItemDTO {

    public DiaryTimelineCompletionItemDTO(
            DiaryViewItemDTO completion, ContentCardDTO card, ContentCardDTO seriesCard) {
        this("DERIVED_COMPLETION", completion, card, seriesCard);
    }

    public DiaryTimelineCompletionItemDTO {
        Objects.requireNonNull(completion, "completion is required");
        if (!"DERIVED_COMPLETION".equals(completion.kind().name())) {
            throw new IllegalArgumentException("completion must be derived");
        }
        if (!"DERIVED_COMPLETION".equals(kind)) {
            throw new IllegalArgumentException("kind must be DERIVED_COMPLETION");
        }
    }
}
