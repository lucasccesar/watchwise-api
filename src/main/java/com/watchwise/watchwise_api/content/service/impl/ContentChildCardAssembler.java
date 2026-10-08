package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentChildCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentCardStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.ContentNavigationDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageSectionsDTO;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.EpisodeSummaryDTO;
import com.watchwise.watchwise_api.content.dto.SeasonSummaryDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
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
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ContentChildCardAssembler {

    private static final int RECENT_EPISODES_LIMIT = 3;

    private final ContentCardAssembler contentCardAssembler;

    public ContentPageSectionsDTO assembleSections(
            ContentDetailsDTO details, ContentCoordinate rootCoordinate, UUID viewerId) {
        return assembleSections(details, null, rootCoordinate, viewerId);
    }

    public ContentPageSectionsDTO assembleSections(
            ContentDetailsDTO details,
            ContentDetailsDTO parentSeriesDetails,
            ContentCoordinate rootCoordinate,
            UUID viewerId) {
        Objects.requireNonNull(details, "details is required");
        Objects.requireNonNull(rootCoordinate, "rootCoordinate is required");
        Objects.requireNonNull(viewerId, "viewerId is required");

        SectionSpecs sections = sectionSpecs(details, parentSeriesDetails, rootCoordinate);
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
        return assembleNavigation(details, null, rootCoordinate, viewerId);
    }

    public ContentNavigationDTO assembleNavigation(
            ContentDetailsDTO details,
            ContentDetailsDTO parentSeasonDetails,
            ContentCoordinate rootCoordinate,
            UUID viewerId) {
        Objects.requireNonNull(details, "details is required");
        Objects.requireNonNull(rootCoordinate, "rootCoordinate is required");
        Objects.requireNonNull(viewerId, "viewerId is required");

        if (rootCoordinate.type() != ContentType.EPISODE) {
            return null;
        }

        EpisodeBoundary boundary = episodeBoundary(parentSeasonDetails);
        if (boundary == null) {
            return null;
        }
        CardSpec previous = adjacentEpisode(boundary, rootCoordinate, -1);
        CardSpec next = adjacentEpisode(boundary, rootCoordinate, 1);
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
                boundary == null ? null : boundary.episodeCount(),
                previous == null ? null : cards.get(previous.coordinate()),
                next == null ? null : cards.get(next.coordinate()));
    }

    private SectionSpecs sectionSpecs(
            ContentDetailsDTO details,
            ContentDetailsDTO parentSeriesDetails,
            ContentCoordinate rootCoordinate) {
        return switch (rootCoordinate.type()) {
            case SERIES -> new SectionSpecs(
                    seriesSeasonSpecs(details.seasons(), rootCoordinate.tmdbId()),
                    List.of(),
                    seriesRecentEpisodeSpecs(details.recentEpisodes(), rootCoordinate.tmdbId()));
            case SEASON -> new SectionSpecs(
                    parentSeriesDetails == null
                            ? List.of()
                            : seriesSeasonSpecs(parentSeriesDetails.seasons(), rootCoordinate.seriesTmdbId()),
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

    private CardSpec adjacentEpisode(
            EpisodeBoundary boundary, ContentCoordinate rootCoordinate, int offset) {
        if (!hasText(rootCoordinate.seriesTmdbId())
                || rootCoordinate.seasonNumber() == null
                || rootCoordinate.episodeNumber() == null
                || rootCoordinate.episodeNumber() <= 0) {
            return null;
        }

        int episodeNumber = rootCoordinate.episodeNumber() + offset;
        if (episodeNumber <= 0
                || (offset > 0 && episodeNumber > boundary.episodeCount())) {
            return null;
        }

        EpisodeSummaryDTO summary = safeList(boundary == null ? null : boundary.episodes()).stream()
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
            List<CardSpec> specs, UUID viewerId) {
        Map<ContentCoordinate, CardSpec> distinctSpecsByCoordinate = new LinkedHashMap<>();
        for (CardSpec spec : safeList(specs)) {
            distinctSpecsByCoordinate.putIfAbsent(spec.coordinate(), spec);
        }
        List<CardSpec> distinctSpecs = new ArrayList<>(distinctSpecsByCoordinate.values());
        if (distinctSpecs.isEmpty()) {
            return Map.of();
        }

        List<ContentCardSpec> cardSpecs = distinctSpecs.stream()
                .map(spec -> new ContentCardSpec(
                        spec.coordinate(), spec.title(), spec.posterPath(), spec.releaseDate(), spec.runtimeMinutes()))
                .toList();
        ContentCardAssembler.AssemblyResult result = contentCardAssembler.assembleWithViewerStates(
                cardSpecs,
                new ContentCardContext(null, null, null, viewerId),
                Set.of(ContentCardFieldSet.STATS, ContentCardFieldSet.VIEWER_STATE));

        Map<ContentCoordinate, ContentChildCardDTO> cards = new LinkedHashMap<>();
        for (CardSpec spec : distinctSpecs) {
            cards.put(spec.coordinate(), toChildCard(
                    spec,
                    result.cardsByCoordinate().get(spec.coordinate()),
                    result.viewerStatesByCoordinate().get(spec.coordinate())));
        }
        return cards;
    }

    private ContentChildCardDTO toChildCard(
            CardSpec spec,
            ContentCardDTO card,
            ContentViewerStateDTO viewerState) {
        ContentCoordinate coordinate = spec.coordinate();
        ContentCardStatsDTO stats = card == null ? null : card.stats();
        ContentStatsResponseDTO statsResponse = stats == null
                ? new ContentStatsResponseDTO(card == null ? null : card.contentId(), null, 0, 0, 0)
                : new ContentStatsResponseDTO(
                        card.contentId(), stats.averageScore(), stats.playsCount(),
                        stats.reviewsCount(), stats.commentsCount());
        return new ContentChildCardDTO(
                card == null ? null : card.contentId(),
                coordinate.type(),
                coordinate.tmdbId(),
                coordinate.seriesTmdbId(),
                coordinate.seasonNumber(),
                coordinate.episodeNumber(),
                spec.title(),
                spec.posterPath(),
                spec.releaseDate(),
                spec.runtimeMinutes(),
                statsResponse,
                viewerState == null ? emptyState() : viewerState);
    }

    private EpisodeBoundary episodeBoundary(ContentDetailsDTO parentSeasonDetails) {
        if (parentSeasonDetails == null) {
            return null;
        }
        List<EpisodeSummaryDTO> episodes = safeList(parentSeasonDetails.episodes());
        Integer listedEpisodeCount = episodes.stream()
                .map(EpisodeSummaryDTO::episodeNumber)
                .filter(Objects::nonNull)
                .filter(episodeNumber -> episodeNumber > 0)
                .max(Integer::compareTo)
                .orElse(null);
        Integer episodeCount = parentSeasonDetails.numberOfEpisodes() != null
                && parentSeasonDetails.numberOfEpisodes() > 0
                ? parentSeasonDetails.numberOfEpisodes()
                : listedEpisodeCount;
        return episodeCount == null ? null : new EpisodeBoundary(episodes, episodeCount);
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

    private record EpisodeBoundary(List<EpisodeSummaryDTO> episodes, Integer episodeCount) {
    }
}
