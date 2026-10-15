package com.watchwise.watchwise_api.dailygame.dto;

public record DailyGameFilmographyActorViewDTO(
        String personTmdbId,
        String name,
        String profileUrl,
        Integer ageAtChallengeDate,
        int sharedWorksCount,
        int majorRoleSharedWorksCount) {
}
