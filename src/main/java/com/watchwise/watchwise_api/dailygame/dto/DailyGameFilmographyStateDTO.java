package com.watchwise.watchwise_api.dailygame.dto;

import java.util.List;

public record DailyGameFilmographyStateDTO(
        boolean majorRoles,
        List<DailyGameActorGuessDTO> guessedActors,
        List<DailyGameFilmographyEntryDTO> entries) {

    public DailyGameFilmographyStateDTO {
        guessedActors = guessedActors == null ? List.of() : List.copyOf(guessedActors);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
