package com.watchwise.watchwise_api.diaryentry.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record DiaryEntriesBatchRequestDTO(
        @NotEmpty
        @Size(max = 500)
        List<UUID> entryIds) {
}
