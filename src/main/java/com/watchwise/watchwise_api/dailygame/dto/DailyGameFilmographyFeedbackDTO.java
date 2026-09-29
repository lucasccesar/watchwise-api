package com.watchwise.watchwise_api.dailygame.dto;

import java.util.List;

public record DailyGameFilmographyFeedbackDTO(
        DailyGameActorGuessDTO guessedActor,
        List<String> sharedMajorRoleWorkKeys,
        List<String> sharedAllRoleWorkKeys,
        List<DailyGameFilmographyEntryDTO> entries) {

    public DailyGameFilmographyFeedbackDTO {
        sharedMajorRoleWorkKeys = sharedMajorRoleWorkKeys == null
                ? List.of() : List.copyOf(sharedMajorRoleWorkKeys);
        sharedAllRoleWorkKeys = sharedAllRoleWorkKeys == null
                ? List.of() : List.copyOf(sharedAllRoleWorkKeys);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
