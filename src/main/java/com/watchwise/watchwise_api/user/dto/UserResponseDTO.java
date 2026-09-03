package com.watchwise.watchwise_api.user.dto;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record UserResponseDTO(
        UUID id,
        String username,
        String email,
        String description,
        String profilePicture,
        Boolean isProfilePublic,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long totalMinutesWatched,
        long minutesWatchedLast30Days,
        long totalTheaterVisits,
        List<GenreCountDTO> genreCountsMovies,
        List<GenreCountDTO> genreCountsSeries,
        String banner,
        long followersCount,
        long followingCount,
        String preferredLanguage,
        String preferredRegion
) {
    public UserResponseDTO(UUID id, String username, String email, String description, String profilePicture,
            Boolean isProfilePublic, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, username, email, description, profilePicture, isProfilePublic, createdAt, updatedAt,
                0L, 0L, 0L, List.of(), List.of(), null, 0L, 0L, null, null);
    }
}
