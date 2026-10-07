package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentChildCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.ContentNavigationDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageSectionsDTO;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.EpisodeSummaryDTO;
import com.watchwise.watchwise_api.content.dto.SeasonSummaryDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
import com.watchwise.watchwise_api.content.service.ContentViewerStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContentChildCardAssembler {

    private static final int RECENT_EPISODES_LIMIT = 3;

    private final ContentStatsService contentStatsService;
    private final ContentViewerStateService contentViewerStateService;

    public ContentPageSectionsDTO assembleSections(
            ContentDetailsDTO details, ContentCoordinate rootCoordinate, UUID viewerId) {
        Objects.requireNonNull(details, "details is required");
        Objects.requireNonNull(rootCoordinate, "rootCoordinate is required");
        Objects.requireNonNull(viewerId, "viewerId is required");

        SectionSpecs sections = sectionSpecs(details, rootCoordinate);
        List<CardSpec> allSpecs = new ArrayList<>();
        allSpecs.addAll(sections.seasons());
        allSpecs.addAll(sections.episodes());
        allSpecs.addAll(sections.recentEpisodes());
        Map<ContentCoordinate, ContentChildCardDTO> cards = assembleCards(allSpecs, viewerId);
        return new ContentPageSectionsDTO(
                toCards(sections.seasons(), cards),
                toCards(sections.episodes(), cards),
                toCards(sections.recentEpisodes(), cards));
    }

    public ContentNavigationDTO assembleNavigation(
            ContentDetailsDTO details, ContentCoordinate rootCoordinate, UUID viewerId) {
        Objects.requireNonNull(details, "details is required");
        Objects.requireNonNull(rootCoordinate, "rootCoordinate is required");
        Objects.requireNonNull(viewerId, "viewerId is required");

        if (rootCoordinate.type() != ContentType.EPISODE) {
            return null;
        }

        CardSpec previous = adjacentEpisode(details, rootCoordinate, -1);
        CardSpec next = adjacentEpisode(details, rootCoordinate, 1);
        List<CardSpec> adjacent = new ArrayList<>();
        if (previous != null) {
            adjacent.add(previous);
        }
        if (next != null) {
            adjacent.add(next);
        }
        Map<ContentCoordinate, ContentChildCardDTO> cards = assembleCards(adjacent, viewerId);
        return new ContentNavigationDTO(
                rootCoordinate.seriesTmdbId(),
                rootCoordinate.seasonNumber(),
                rootCoordinate.episodeNumber(),
                details.numberOfEpisodes(),
                previous == null ? null : cards.get(previous.coordinate()),
                next == null ? null : cards.get(next.coordinate()));
    }

    private SectionSpecs sectionSpecs(ContentDetailsDTO details, ContentCoordinate rootCoordinate) {
        return switch (rootCoordinate.type()) {
            case SERIES -> new SectionSpecs(
                    seriesSeasonSpecs(details.seasons(), rootCoordinate.tmdbId()),
                    List.of(),
                    seriesRecentEpisodeSpecs(details.recentEpisodes(), rootCoordinate.tmdbId()));
            case SEASON -> new SectionSpecs(
                    List.of(),
                    seasonEpisodeSpecs(
                            details.episodes(), rootCoordinate.seriesTmdbId(), rootCoordinate.seasonNumber()),
                    List.of());
            case MOVIE, EPISODE -> new SectionSpecs(List.of(), List.of(), List.of());
        };
    }

    private List<CardSpec> seriesSeasonSpecs(List<SeasonSummaryDTO> summaries, String seriesTmdbId) {
        if (!hasText(seriesTmdbId) || summaries == null) {
            return List.of();
        }
        return summaries.stream()
                .filter(Objects::nonNull)
                .filter(summary -> summary.seasonNumber() != null && summary.seasonNumber() > 0)
                .map(summary -> new CardSpec(
                        new ContentCoordinate(
                                ContentType.SEASON, null, seriesTmdbId, summary.seasonNumber(), null),
                        summary.name(),
                        summary.posterPath(),
                        summary.airDate(),
                        null))
                .toList();
    }

    private List<CardSpec> seriesRecentEpisodeSpecs(
            List<EpisodeSummaryDTO> summaries, String seriesTmdbId) {
        if (!hasText(seriesTmdbId) || summaries == null) {
            return List.of();
        }
        return summaries.stream()
                .filter(Objects::nonNull)
                .filter(summary -> validEpisodeCoordinate(summary.seasonNumber(), summary.episodeNumber()))
                .limit(RECENT_EPISODES_LIMIT)
                .map(summary -> episodeSpec(seriesTmdbId, summary.seasonNumber(), summary))
                .toList();
    }

    private List<CardSpec> seasonEpisodeSpecs(
            List<EpisodeSummaryDTO> summaries, String seriesTmdbId, Integer seasonNumber) {
        if (!hasText(seriesTmdbId) || seasonNumber == null || seasonNumber < 0 || summaries == null) {
            return List.of();
        }
        return summaries.stream()
                .filter(Objects::nonNull)
                .filter(summary -> summary.episodeNumber() != null && summary.episodeNumber() > 0)
                .map(summary -> episodeSpec(seriesTmdbId, seasonNumber, summary))
                .toList();
    }

    private CardSpec adjacentEpisode(ContentDetailsDTO details, ContentCoordinate rootCoordinate, int offset) {
        if (!hasText(rootCoordinate.seriesTmdbId())
                || rootCoordinate.seasonNumber() == null
                || rootCoordinate.episodeNumber() == null
                || rootCoordinate.episodeNumber() <= 0) {
            return null;
        }

        int episodeNumber = rootCoordinate.episodeNumber() + offset;
        if (episodeNumber <= 0
                || (offset > 0 && details.numberOfEpisodes() != null
                && episodeNumber > details.numberOfEpisodes())) {
            return null;
        }

        EpisodeSummaryDTO summary = safeList(details.episodes()).stream()
                .filter(candidate -> Objects.equals(candidate.seasonNumber(), rootCoordinate.seasonNumber()))
                .filter(candidate -> Objects.equals(candidate.episodeNumber(), episodeNumber))
                .findFirst()
                .orElse(null);
        return summary == null
                ? new CardSpec(
                        new ContentCoordinate(
                                ContentType.EPISODE,
                                null,
                                rootCoordinate.seriesTmdbId(),
                                rootCoordinate.seasonNumber(),
                                episodeNumber),
                        null,
                        null,
                        null,
                        null)
                : episodeSpec(rootCoordinate.seriesTmdbId(), rootCoordinate.seasonNumber(), summary);
    }

    private CardSpec episodeSpec(
            String seriesTmdbId, Integer seasonNumber, EpisodeSummaryDTO summary) {
        return new CardSpec(
                new ContentCoordinate(
                        ContentType.EPISODE,
                        null,
                        seriesTmdbId,
                        seasonNumber,
                        summary.episodeNumber()),
                summary.name(),
                summary.stillPath(),
                summary.airDate(),
                summary.runtime());
    }

    private Map<ContentCoordinate, ContentChildCardDTO> assembleCards(
            Collection<CardSpec> specs, UUID viewerId) {
        List<CardSpec> distinctSpecs = safeList(specs).stream()
                .collect(Collectors.toMap(
                        CardSpec::coordinate,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new))
                .values().stream()
                .toList();
        if (distinctSpecs.isEmpty()) {
            return Map.of();
        }

        List<ContentCoordinate> coordinates = distinctSpecs.stream()
                .map(CardSpec::coordinate)
                .toList();
        ContentViewerStateService.Resolution resolution = contentViewerStateService.resolve(
                viewerId, coordinates, Map.of());
        Map<UUID, ContentStatsResponseDTO> statsByContentId = statsByContentId(
                distinctSpecs.stream()
                        .map(spec -> resolution.existingContentIdsByCoordinate().get(spec.coordinate()))
                        .toList());

        return distinctSpecs.stream().collect(Collectors.toMap(
                CardSpec::coordinate,
                spec -> toCard(spec, resolution, statsByContentId),
                (left, right) -> left,
                LinkedHashMap::new));
    }

    private Map<UUID, ContentStatsResponseDTO> statsByContentId(Collection<UUID> contentIds) {
        List<UUID> distinctContentIds = safeList(contentIds).stream().distinct().toList();
        if (distinctContentIds.isEmpty()) {
            return Map.of();
        }
        List<ContentStatsResponseDTO> stats = contentStatsService.getStatsBatch(distinctContentIds);
        if (stats == null) {
            return Map.of();
        }
        return stats.stream()
                .filter(Objects::nonNull)
                .filter(stat -> stat.contentId() != null)
                .collect(Collectors.toMap(
                        ContentStatsResponseDTO::contentId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private ContentChildCardDTO toCard(
            CardSpec spec,
            ContentViewerStateService.Resolution resolution,
            Map<UUID, ContentStatsResponseDTO> statsByContentId) {
        UUID contentId = resolution.existingContentIdsByCoordinate().get(spec.coordinate());
        ContentStatsResponseDTO stats = contentId == null
                ? zeroStats()
                : statsByContentId.getOrDefault(contentId, zeroStats(contentId));
        ContentViewerStateDTO viewerState = resolution.statesByCoordinate()
                .getOrDefault(spec.coordinate(), emptyState());
        ContentCoordinate coordinate = spec.coordinate();
        return new ContentChildCardDTO(
                contentId,
                coordinate.type(),
                coordinate.tmdbId(),
                coordinate.seriesTmdbId(),
                coordinate.seasonNumber(),
                coordinate.episodeNumber(),
                spec.title(),
                spec.posterPath(),
                spec.releaseDate(),
                spec.runtimeMinutes(),
                stats,
                viewerState);
    }

    private List<ContentChildCardDTO> toCards(
            List<CardSpec> specs, Map<ContentCoordinate, ContentChildCardDTO> cards) {
        return specs.stream().map(spec -> cards.get(spec.coordinate())).toList();
    }

    private boolean validEpisodeCoordinate(Integer seasonNumber, Integer episodeNumber) {
        return seasonNumber != null && seasonNumber >= 0 && episodeNumber != null && episodeNumber > 0;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private <T> List<T> safeList(Collection<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    private ContentStatsResponseDTO zeroStats() {
        return new ContentStatsResponseDTO(null, null, 0, 0, 0);
    }

    private ContentStatsResponseDTO zeroStats(UUID contentId) {
        return new ContentStatsResponseDTO(contentId, null, 0, 0, 0);
    }

    private ContentViewerStateDTO emptyState() {
        return new ContentViewerStateDTO(
                WatchStatus.UNWATCHED,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                false,
                null,
                false,
                null,
                List.of(),
                null,
                null);
    }

    private record CardSpec(
            ContentCoordinate coordinate,
            String title,
            String posterPath,
            LocalDate releaseDate,
            Integer runtimeMinutes) {
    }

    private record SectionSpecs(
            List<CardSpec> seasons,
            List<CardSpec> episodes,
            List<CardSpec> recentEpisodes) {
    }
}
