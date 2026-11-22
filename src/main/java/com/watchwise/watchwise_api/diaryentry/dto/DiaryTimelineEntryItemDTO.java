package com.watchwise.watchwise_api.diaryentry.dto;

import java.util.Objects;

public record DiaryTimelineEntryItemDTO(
        String kind,
        DiaryEntryResponseDTO entry) implements DiaryTimelineResponseItemDTO {

    public DiaryTimelineEntryItemDTO(DiaryEntryResponseDTO entry) {
        this("PERSISTED_ENTRY", entry);
    }

    public DiaryTimelineEntryItemDTO {
        Objects.requireNonNull(entry, "entry is required");
        if (!"PERSISTED_ENTRY".equals(kind)) {
            throw new IllegalArgumentException("kind must be PERSISTED_ENTRY");
        }
    }
}
