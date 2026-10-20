package com.watchwise.watchwise_api.user.dto;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.userblock.entity.UserBlockRelationship;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicUserProfileDTO(
        UUID id,
        String username,
        String name,
        String description,
        String profilePicture,
        Boolean isProfilePublic,
        LocalDateTime createdAt,
        Long totalMinutesWatchedMovies,
        Long totalMinutesWatchedEpisodes,
        Long minutesWatchedMoviesLast30Days,
        Long minutesWatchedEpisodesLast30Days,
        Long totalTheaterVisits,
        List<GenreCountDTO> genreCountsMovies,
        List<GenreCountDTO> genreCountsSeries,
        String banner,
        Long followersCount,
        Long followingCount,
        ViewerProfileRelation viewerRelation,
        UserBlockRelationship blockRelationship,
        Boolean isContentRestrictedByBlock
) {
    public PublicUserProfileDTO(UUID id, String username, String name, String description, String profilePicture,
            Boolean isProfilePublic, LocalDateTime createdAt, Long totalMinutesWatchedMovies,
            Long totalMinutesWatchedEpisodes, Long minutesWatchedMoviesLast30Days,
            Long minutesWatchedEpisodesLast30Days, Long totalTheaterVisits, List<GenreCountDTO> genreCountsMovies,
            List<GenreCountDTO> genreCountsSeries, String banner, Long followersCount, Long followingCount,
            ViewerProfileRelation viewerRelation) {
        this(id, username, name, description, profilePicture, isProfilePublic, createdAt,
                totalMinutesWatchedMovies, totalMinutesWatchedEpisodes, minutesWatchedMoviesLast30Days,
                minutesWatchedEpisodesLast30Days, totalTheaterVisits, genreCountsMovies, genreCountsSeries, banner,
                followersCount, followingCount, viewerRelation, UserBlockRelationship.NONE, false);
    }

    public PublicUserProfileDTO(UUID id, String username, String description, String profilePicture,
            Boolean isProfilePublic, LocalDateTime createdAt, long totalMinutesWatchedMovies,
            long totalMinutesWatchedEpisodes, long minutesWatchedMoviesLast30Days,
            long minutesWatchedEpisodesLast30Days, long totalTheaterVisits, List<GenreCountDTO> genreCountsMovies,
            List<GenreCountDTO> genreCountsSeries, String banner, long followersCount, long followingCount) {
        this(id, username, username, description, profilePicture, isProfilePublic, createdAt,
                totalMinutesWatchedMovies, totalMinutesWatchedEpisodes, minutesWatchedMoviesLast30Days,
                minutesWatchedEpisodesLast30Days, totalTheaterVisits, genreCountsMovies, genreCountsSeries,
                banner, followersCount, followingCount, ViewerProfileRelation.NOT_FOLLOWING,
                UserBlockRelationship.NONE, false);
    }

}
