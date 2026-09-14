package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentProductionStatus;
import com.watchwise.watchwise_api.content.dto.ContentStateDTO;
import com.watchwise.watchwise_api.content.dto.ReleaseStatus;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.repository.WatchedEpisodeCoordinate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ContentStateResolver {

    public ContentStateDTO resolve(
            Content content,
            ContentSchedule schedule,
            Set<java.util.UUID> watchedDirectContentIds,
            Set<WatchedEpisodeCoordinate> watchedEpisodeCoordinates,
            Clock clock) {
        Objects.requireNonNull(content, "content is required");
        Objects.requireNonNull(schedule, "schedule is required");
        Objects.requireNonNull(clock, "clock is required");

        WatchResolution watchResolution = resolveWatchStatus(
                content, schedule, watchedDirectContentIds, watchedEpisodeCoordinates, clock);
        ReleaseStatus releaseStatus = resolveReleaseStatus(schedule.releaseDate(), clock);
        ContentProductionStatus productionStatus = content.getType() == ContentType.EPISODE
                ? null
                : normalizeProductionStatus(schedule.externalStatus());

        return new ContentStateDTO(
                watchResolution.status(),
                releaseStatus,
                productionStatus,
                watchResolution.watchedEpisodeCount(),
                watchResolution.releasedEpisodeCount());
    }

    private WatchResolution resolveWatchStatus(
            Content content,
            ContentSchedule schedule,
            Set<java.util.UUID> watchedDirectContentIds,
            Set<WatchedEpisodeCoordinate> watchedEpisodeCoordinates,
            Clock clock) {
        if (content.getType() == ContentType.MOVIE || content.getType() == ContentType.EPISODE) {
            boolean watched = content.getId() != null
                    && watchedDirectContentIds != null
                    && watchedDirectContentIds.contains(content.getId());
            return new WatchResolution(watched ? WatchStatus.WATCHED : WatchStatus.UNWATCHED, null, null);
        }

        if (!schedule.complete()) {
            return new WatchResolution(WatchStatus.UNKNOWN, null, null);
        }

        Set<WatchedEpisodeCoordinate> releasedEpisodes = releasedEpisodeCoordinates(content, schedule, clock);
        Set<WatchedEpisodeCoordinate> watched = watchedEpisodeCoordinates == null
                ? Set.of()
                : watchedEpisodeCoordinates;
        int watchedEpisodeCount = (int) releasedEpisodes.stream().filter(watched::contains).count();
        int releasedEpisodeCount = releasedEpisodes.size();
        WatchStatus status = watchedEpisodeCount == 0
                ? WatchStatus.UNWATCHED
                : watchedEpisodeCount == releasedEpisodeCount
                ? WatchStatus.WATCHED
                : WatchStatus.PARTIALLY_WATCHED;
        return new WatchResolution(status, watchedEpisodeCount, releasedEpisodeCount);
    }

    private Set<WatchedEpisodeCoordinate> releasedEpisodeCoordinates(
            Content content, ContentSchedule schedule, Clock clock) {
        LocalDate today = LocalDate.now(clock);
        Predicate<ContentScheduleEpisode> belongsToContent = episode -> switch (content.getType()) {
            case SEASON -> Objects.equals(content.getSeasonNumber(), episode.seasonNumber());
            case SERIES -> Objects.equals(schedule.seriesTmdbId(), episode.seriesTmdbId());
            default -> false;
        };

        return schedule.episodes().stream()
                .filter(Objects::nonNull)
                .filter(this::isRegularEpisode)
                .filter(belongsToContent)
                .filter(episode -> !episode.releaseDate().isAfter(today))
                .map(episode -> toCoordinate(episode, schedule))
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean isRegularEpisode(ContentScheduleEpisode episode) {
        return episode.seasonNumber() != null
                && episode.seasonNumber() > 0
                && episode.episodeNumber() != null
                && episode.episodeNumber() > 0
                && episode.releaseDate() != null;
    }

    private WatchedEpisodeCoordinate toCoordinate(ContentScheduleEpisode episode, ContentSchedule schedule) {
        String seriesTmdbId = episode.seriesTmdbId() == null
                ? schedule.seriesTmdbId()
                : episode.seriesTmdbId();
        if (seriesTmdbId == null || seriesTmdbId.isBlank()) {
            return null;
        }
        return new WatchedEpisodeCoordinate(seriesTmdbId, episode.seasonNumber(), episode.episodeNumber());
    }

    private ReleaseStatus resolveReleaseStatus(LocalDate releaseDate, Clock clock) {
        if (releaseDate == null) {
            return ReleaseStatus.UNKNOWN;
        }
        return releaseDate.isAfter(LocalDate.now(clock)) ? ReleaseStatus.UPCOMING : ReleaseStatus.RELEASED;
    }

    private ContentProductionStatus normalizeProductionStatus(String externalStatus) {
        if (externalStatus == null) {
            return ContentProductionStatus.UNKNOWN;
        }
        return switch (externalStatus.trim()) {
            case "Released", "Ended" -> ContentProductionStatus.FINISHED;
            case "Canceled" -> ContentProductionStatus.CANCELLED;
            case "Returning Series", "In Production", "Post Production" -> ContentProductionStatus.IN_PROGRESS;
            case "Planned", "Pilot", "Rumored" -> ContentProductionStatus.PRE_RELEASE;
            default -> ContentProductionStatus.UNKNOWN;
        };
    }

    private record WatchResolution(
            WatchStatus status,
            Integer watchedEpisodeCount,
            Integer releasedEpisodeCount) {
    }
}
