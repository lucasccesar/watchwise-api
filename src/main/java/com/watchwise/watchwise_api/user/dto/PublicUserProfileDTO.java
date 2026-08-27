package com.watchwise.watchwise_api.user.dto;

import com.watchwise.watchwise_api.common.dto.GenreWatchTimeDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PublicUserProfileDTO(
        UUID id,
        String username,
        String description,
        String profilePicture,
        Boolean isProfilePublic,
        LocalDateTime createdAt,
        long totalMinutesWatched,
        long minutesWatchedLast30Days,
        List<GenreWatchTimeDTO> genreMinutesWatched,
        List<GenreWatchTimeDTO> genreMinutesWatchedLast30Days
) {
}
