package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.diarycompletion.model.CompletedPass;

import java.util.List;
import java.util.Objects;

public record DiaryEntryCreationResultDTO(
        DiaryEntryResponseDTO entry,
        List<EventResult> events,
        List<CompletionDescriptor> completions) {

    public DiaryEntryCreationResultDTO {
        events = List.copyOf(Objects.requireNonNull(events, "events are required"));
        completions = List.copyOf(Objects.requireNonNull(completions, "completions are required"));
    }

    public record CompletionDescriptor(MediaReference content, CompletedPass pass) {
        public CompletionDescriptor {
            Objects.requireNonNull(content, "completion content is required");
            Objects.requireNonNull(pass, "completed pass is required");
        }
    }

    public record EventResult(WatchMutationResult mutation, DiaryEntryResponseDTO entry) {
        public EventResult {
            Objects.requireNonNull(mutation, "watch mutation is required");
            Objects.requireNonNull(entry, "diary entry response is required");
        }
    }

}
