package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbImagePathBuilder;
import com.watchwise.watchwise_api.content.dto.ContentCardStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
import com.watchwise.watchwise_api.content.service.ContentViewerStateService;
import com.watchwise.watchwise_api.content.service.impl.TmdbVoteAverageBatchResolver;
import com.watchwise.watchwise_api.person.dto.PersonCreditViewDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PersonCreditViewAssembler {

    private static final int STATS_BATCH_SIZE = 100;

    private final PersonCreditViewerStateResolver viewerStateResolver;
    private final ContentStatsService contentStatsService;
    private final TmdbVoteAverageBatchResolver tmdbVoteAverageBatchResolver;

    @Autowired
    public PersonCreditViewAssembler(
            PersonCreditViewerStateResolver viewerStateResolver,
            ContentStatsService contentStatsService,
            TmdbVoteAverageBatchResolver tmdbVoteAverageBatchResolver) {
        this.viewerStateResolver = viewerStateResolver;
        this.contentStatsService = contentStatsService;
        this.tmdbVoteAverageBatchResolver = tmdbVoteAverageBatchResolver;
    }

    public PersonCreditViewAssembler(
            PersonCreditViewerStateResolver viewerStateResolver,
            ContentStatsService contentStatsService) {
        this(viewerStateResolver, contentStatsService, null);
    }

    public List<PersonCreditViewDTO> assemble(UUID viewerId, List<PersonFilmographyCredit> credits) {
        ContentViewerStateService.Resolution resolution = viewerStateResolver.resolve(viewerId, credits);
        List<UUID> existingContentIds = resolution.existingContentIdsByCoordinate().values().stream()
                .distinct().toList();
        Map<UUID, ContentStatsResponseDTO> statsByContentId = loadStats(existingContentIds);
        return credits.stream().map(credit -> toView(credit, resolution, statsByContentId)).toList();
    }

    public List<PersonCreditViewDTO> enrichTmdbVoteAverages(
            List<PersonCreditViewDTO> pageCredits, String language) {
        if (pageCredits == null || pageCredits.isEmpty()) {
            return List.of();
        }
        if (tmdbVoteAverageBatchResolver == null) {
            return pageCredits;
        }
        List<ContentCoordinate> coordinates = pageCredits.stream()
                .map(PersonCreditViewAssembler::coordinate)
                .toList();
        Map<ContentCoordinate, Double> averagesByCoordinate = tmdbVoteAverageBatchResolver.resolve(
                coordinates, language);
        return pageCredits.stream()
                .map(credit -> credit.withTmdbVoteAverage(averagesByCoordinate.get(coordinate(credit))))
                .toList();
    }

    private Map<UUID, ContentStatsResponseDTO> loadStats(List<UUID> contentIds) {
        Map<UUID, ContentStatsResponseDTO> stats = new LinkedHashMap<>();
        for (int from = 0; from < contentIds.size(); from += STATS_BATCH_SIZE) {
            List<UUID> batch = contentIds.subList(from, Math.min(from + STATS_BATCH_SIZE, contentIds.size()));
            List<ContentStatsResponseDTO> responses = tmdbVoteAverageBatchResolver == null
                    ? contentStatsService.getStatsBatch(batch)
                    : contentStatsService.getCommunityStatsBatch(batch);
            if (responses != null) {
                responses.stream().filter(response -> response != null && response.contentId() != null)
                        .forEach(response -> stats.put(response.contentId(), response));
            }
        }
        return stats;
    }

    private PersonCreditViewDTO toView(PersonFilmographyCredit credit,
                                       ContentViewerStateService.Resolution resolution,
                                       Map<UUID, ContentStatsResponseDTO> statsByContentId) {
        ContentCoordinate coordinate = coordinate(credit);
        UUID contentId = resolution.existingContentIdsByCoordinate().get(coordinate);
        ContentStatsResponseDTO stats = contentId == null ? null : statsByContentId.get(contentId);
        ContentViewerStateDTO state = resolution.statesByCoordinate().get(coordinate);
        if (state == null) state = emptyState();
        ContentCardStatsDTO cardStats = stats == null
                ? new ContentCardStatsDTO(null, 0L, 0L, 0L)
                : new ContentCardStatsDTO(stats.averageScore(), stats.playsCount(), stats.reviewsCount(), stats.commentsCount());
        ContentCardViewerStateDTO viewerState = new ContentCardViewerStateDTO(state.watchStatus(), state.myRating(),
                state.lastWatchedDate(), state.lastWatchNumber(), state.watchedInTheater(), state.playsCount(),
                state.inWatchlist(), state.dropped(), state.watchedEpisodeCount(), state.releasedEpisodeCount(),
                state.currentRatingVersion(), state.reviewsCount(), state.reviewIds());
        String posterUrl = TmdbImagePathBuilder.posterPath(credit.posterPath());
        ContentPreviewStatus previewStatus = isBlank(credit.title()) || posterUrl == null || credit.releaseDate() == null
                ? ContentPreviewStatus.PARTIAL : ContentPreviewStatus.AVAILABLE;
        return new PersonCreditViewDTO(credit.tmdbId(), credit.type(), credit.title(), posterUrl, credit.releaseDate(),
                credit.characters(), credit.jobs(), credit.participation(), credit.creditedEpisodeCount(),
                credit.popularity(), cardStats, viewerState, !state.lists().isEmpty(), previewStatus);
    }

    private static ContentCoordinate coordinate(PersonFilmographyCredit credit) {
        ContentType type = credit.type() == MovieOrSeriesType.MOVIE ? ContentType.MOVIE : ContentType.SERIES;
        return new ContentCoordinate(type, credit.tmdbId(), null, null, null);
    }

    private static ContentCoordinate coordinate(PersonCreditViewDTO credit) {
        ContentType type = credit.type() == MovieOrSeriesType.MOVIE ? ContentType.MOVIE : ContentType.SERIES;
        return new ContentCoordinate(type, credit.tmdbId(), null, null, null);
    }

    private static ContentViewerStateDTO emptyState() {
        return new ContentViewerStateDTO(WatchStatus.UNWATCHED, null, null, null, null, null, 0,
                null, false, null, false, null, List.of(), null, null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
