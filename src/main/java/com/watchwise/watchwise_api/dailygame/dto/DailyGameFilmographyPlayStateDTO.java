package com.watchwise.watchwise_api.dailygame.dto;

import java.util.List;

public record DailyGameFilmographyPlayStateDTO(
        boolean majorRoles,
        List<DailyGameFilmographyActorViewDTO> guessedActors,
        List<DailyGameFilmographyWorkViewDTO> entries,
        int attemptsUsed,
        int attemptsRemaining,
        int maxAttempts) {

    public DailyGameFilmographyPlayStateDTO {
        guessedActors = guessedActors == null ? List.of() : List.copyOf(guessedActors);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
