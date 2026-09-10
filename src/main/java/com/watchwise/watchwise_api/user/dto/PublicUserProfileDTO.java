package com.watchwise.watchwise_api.user.dto;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;

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
        long totalMinutesWatchedMovies,
        long totalMinutesWatchedEpisodes,
        long minutesWatchedMoviesLast30Days,
        long minutesWatchedEpisodesLast30Days,
        long totalTheaterVisits,
        List<GenreCountDTO> genreCountsMovies,
        List<GenreCountDTO> genreCountsSeries,
        String banner,
        long followersCount,
        long followingCount
) {
}
