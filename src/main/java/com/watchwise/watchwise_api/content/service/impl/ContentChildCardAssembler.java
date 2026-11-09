package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
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
import com.watchwise.watchwise_api.content.service.EpisodeNavigationContext;
import com.watchwise.watchwise_api.content.service.ContentViewerStateService;
import org.springframework.beans.factory.annotation.Autowired;
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
public class ContentChildCardAssembler {

    private static final int RECENT_EPISODES_LIMIT = 4;

    private final ContentCardAssembler contentCardAssembler;
    private final ContentPageSeasonStateService seasonStateService;

    @Autowired
    public ContentChildCardAssembler(
            ContentCardAssembler contentCardAssembler,
            ContentPageSeasonStateService seasonStateService) {
        this.contentCardAssembler = contentCardAssembler;
        this.seasonStateService = seasonStateService;
    }

    public ContentChildCardAssembler(ContentCardAssembler contentCardAssembler) {
        this(contentCardAssembler, null);
    }

    public ContentPageSectionsDTO assembleSections(
            ContentDetailsDTO details, ContentCoordinate rootCoordinate, UUID viewerId) {
        return assembleSections(details, null, rootCoordinate, viewerId);
    }

    public ContentPageSectionsDTO assembleSections(
            ContentDetailsDTO details,
            ContentDetailsDTO parentSeriesDetails,
            ContentCoordinate rootCoordinate,
            UUID viewerId) {
        return assembleSections(details, parentSeriesDetails, null, rootCoordinate, viewerId);
    }

    public ContentPageSectionsDTO assembleSectionsWithSeasonSummaries(
            ContentDetailsDTO details,
            List<SeasonSummaryDTO> siblingSeasons,
            ContentCoordinate rootCoordinate,
            UUID viewerId,
            String preferredRegion,
            String preferredLanguage) {
        return assembleSections(
                details, null, siblingSeasons, rootCoordinate, viewerId, preferredRegion, preferredLanguage);
    }

    private ContentPageSectionsDTO assembleSections(
            ContentDetailsDTO details,
            ContentDetailsDTO parentSeriesDetails,
            List<SeasonSummaryDTO> siblingSeasons,
            ContentCoordinate rootCoordinate,
            UUID viewerId) {
        return assembleSections(details, parentSeriesDetails, siblingSeasons, rootCoordinate, viewerId, null, null);
    }

    private ContentPageSectionsDTO assembleSections(
            ContentDetailsDTO details,
            ContentDetailsDTO parentSeriesDetails,
            List<SeasonSummaryDTO> siblingSeasons,
            ContentCoordinate rootCoordinate,
            UUID viewerId,
            String preferredRegion,
            String preferredLanguage) {
        Objects.requireNonNull(details, "details is required");
        Objects.requireNonNull(rootCoordinate, "rootCoordinate is required");
        Objects.requireNonNull(viewerId, "viewerId is required");

        SectionSpecs sections = sectionSpecs(details, parentSeriesDetails, siblingSeasons, rootCoordinate);
        List<CardSpec> allSpecs = new ArrayList<>();
        allSpecs.addAll(sections.seasons());
        allSpecs.addAll(sections.episodes());
        allSpecs.addAll(sections.recentEpisodes());
        Map<ContentCoordinate, ContentChildCardDTO> cards = assembleCards(allSpecs, viewerId);
        if (siblingSeasons != null
                && (rootCoordinate.type() == ContentType.SEASON || rootCoordinate.type() == ContentType.SERIES)
                && seasonStateService != null) {
            cards = resolveSeasonViewerStates(
                    sections.seasons(), cards, siblingSeasons, rootCoordinate,
                    viewerId, preferredRegion, preferredLanguage);
        }
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
        return assembleNavigationCards(rootCoordinate, boundary.episodeCount(), previous, next, viewerId);
    }

    public ContentNavigationDTO assembleNavigation(
            EpisodeNavigationContext context, ContentCoordinate rootCoordinate, UUID viewerId) {
        return assembleNavigation(context, rootCoordinate, viewerId, null, null);
    }

    public ContentNavigationDTO assembleNavigation(
            EpisodeNavigationContext context,
            ContentCoordinate rootCoordinate,
            UUID viewerId,
            ContentViewerStateService.Resolution viewerState,
            Map<UUID, ContentStatsResponseDTO> statsByContentId) {
        Objects.requireNonNull(rootCoordinate, "rootCoordinate is required");
        Objects.requireNonNull(viewerId, "viewerId is required");
        if (rootCoordinate.type() != ContentType.EPISODE || context == null) {
            return null;
        }

        CardSpec previous = context.previousEpisode() == null
                ? null
                : episodeSpec(rootCoordinate.seriesTmdbId(),
                        context.previousEpisode().seasonNumber(), context.previousEpisode());
        CardSpec next = context.nextEpisode() == null
                ? null
                : episodeSpec(rootCoordinate.seriesTmdbId(),
                        context.nextEpisode().seasonNumber(), context.nextEpisode());
        return assembleNavigationCards(
                rootCoordinate, context.seasonEpisodeCount(), previous, next, viewerId,
                viewerState, statsByContentId);
    }

    private ContentNavigationDTO assembleNavigationCards(
            ContentCoordinate rootCoordinate,
            Integer seasonEpisodeCount,
            CardSpec previous,
            CardSpec next,
            UUID viewerId) {
        return assembleNavigationCards(rootCoordinate, seasonEpisodeCount, previous, next, viewerId, null, null);
    }

    private ContentNavigationDTO assembleNavigationCards(
            ContentCoordinate rootCoordinate,
            Integer seasonEpisodeCount,
            CardSpec previous,
            CardSpec next,
            UUID viewerId,
            ContentViewerStateService.Resolution viewerState,
            Map<UUID, ContentStatsResponseDTO> statsByContentId) {
        List<CardSpec> adjacent = new ArrayList<>();
        if (previous != null) {
            adjacent.add(previous);
        }
        if (next != null) {
            adjacent.add(next);
        }
        Map<ContentCoordinate, ContentChildCardDTO> cards = assembleCards(
                adjacent, viewerId, viewerState, statsByContentId);
        return new ContentNavigationDTO(
                rootCoordinate.seriesTmdbId(),
                rootCoordinate.seasonNumber(),
                rootCoordinate.episodeNumber(),
                seasonEpisodeCount,
                previous == null ? null : cards.get(previous.coordinate()),
                next == null ? null : cards.get(next.coordinate()));
    }

    private SectionSpecs sectionSpecs(
            ContentDetailsDTO details,
            ContentDetailsDTO parentSeriesDetails,
            List<SeasonSummaryDTO> siblingSeasons,
            ContentCoordinate rootCoordinate) {
        return switch (rootCoordinate.type()) {
            case SERIES -> new SectionSpecs(
                    seriesSeasonSpecs(details.seasons(), rootCoordinate.tmdbId()),
                    List.of(),
                    seriesRecentEpisodeSpecs(details.recentEpisodes(), rootCoordinate.tmdbId()));
            case SEASON -> new SectionSpecs(
                    siblingSeasons != null
                            ? seriesSeasonSpecs(siblingSeasons, rootCoordinate.seriesTmdbId())
                            : parentSeriesDetails == null
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
                        null,
                        summary.posterPath(),
                        summary.airDate(),
                        null,
                        summary.airedEpisodeCount(),
                        summary.tmdbVoteAverage(),
                        true))
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
                        null,
                        null,
                        null,
                        null,
                        false)
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
                summary.overview(),
                summary.stillPath(),
                summary.airDate(),
                summary.runtime(),
                null,
                summary.tmdbVoteAverage(),
                true);
    }

    private Map<ContentCoordinate, ContentChildCardDTO> assembleCards(
            List<CardSpec> specs, UUID viewerId) {
        return assembleCards(specs, viewerId, null, null);
    }

    private Map<ContentCoordinate, ContentChildCardDTO> assembleCards(
            List<CardSpec> specs,
            UUID viewerId,
            ContentViewerStateService.Resolution viewerState,
            Map<UUID, ContentStatsResponseDTO> statsByContentId) {
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
                        spec.coordinate(), spec.title(), spec.posterPath(), spec.releaseDate(),
                        spec.runtimeMinutes(), List.of(), spec.tmdbVoteAverage(), spec.tmdbVoteAverageResolved()))
                .toList();
        ContentCardAssembler.AssemblyResult result = contentCardAssembler.assembleWithViewerStates(
                cardSpecs,
                new ContentCardContext(TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, null, null, viewerId),
                Set.of(ContentCardFieldSet.STATS, ContentCardFieldSet.VIEWER_STATE),
                false,
                viewerState,
                statsByContentId);

        Map<ContentCoordinate, ContentChildCardDTO> cards = new LinkedHashMap<>();
        for (CardSpec spec : distinctSpecs) {
            cards.put(spec.coordinate(), toChildCard(
                    spec,
                    result.cardsByCoordinate().get(spec.coordinate()),
                    result.viewerStatesByCoordinate().get(spec.coordinate())));
        }
        return cards;
    }

    private Map<ContentCoordinate, ContentChildCardDTO> resolveSeasonViewerStates(
            List<CardSpec> seasonSpecs,
            Map<ContentCoordinate, ContentChildCardDTO> cards,
            List<SeasonSummaryDTO> siblingSeasons,
            ContentCoordinate rootCoordinate,
            UUID viewerId,
            String preferredRegion,
            String preferredLanguage) {
        Map<ContentCoordinate, ContentViewerStateDTO> currentStates = new LinkedHashMap<>();
        for (CardSpec spec : seasonSpecs) {
            ContentChildCardDTO card = cards.get(spec.coordinate());
            if (card != null) {
                currentStates.put(spec.coordinate(), card.viewerState());
            }
        }
        ContentPageSeasonStateService.Resolution resolution = seasonStateService.resolve(
                viewerId,
                rootCoordinate.type() == ContentType.SERIES
                        ? rootCoordinate.tmdbId()
                        : rootCoordinate.seriesTmdbId(),
                preferredRegion,
                preferredLanguage,
                seasonSpecs.stream().map(CardSpec::coordinate).toList(),
                siblingSeasons,
                currentStates);
        Map<ContentCoordinate, ContentChildCardDTO> result = new LinkedHashMap<>(cards);
        resolution.viewerStates().forEach((coordinate, viewerState) -> {
            ContentChildCardDTO card = result.get(coordinate);
            if (card != null) {
                Integer airedEpisodeCount = resolution.airedEpisodeCounts().get(coordinate);
                result.put(coordinate, new ContentChildCardDTO(
                        card.contentId(),
                        card.type(),
                        card.tmdbId(),
                        card.seriesTmdbId(),
                        card.seasonNumber(),
                        airedEpisodeCount == null ? card.airedEpisodeCount() : airedEpisodeCount,
                        card.episodeNumber(),
                        card.title(),
                        card.overview(),
                        card.posterPath(),
                        card.releaseDate(),
                        card.runtimeMinutes(),
                        card.stats(),
                        viewerState));
            }
        });
        return result;
    }

    private ContentChildCardDTO toChildCard(
            CardSpec spec,
            ContentCardDTO card,
            ContentViewerStateDTO viewerState) {
        ContentCoordinate coordinate = spec.coordinate();
        ContentCardStatsDTO stats = card == null ? null : card.stats();
        ContentStatsResponseDTO statsResponse = stats == null
                ? new ContentStatsResponseDTO(
                        card == null ? null : card.contentId(), null, 0, 0, 0,
                        card == null ? null : card.tmdbVoteAverage())
                : new ContentStatsResponseDTO(
                        card.contentId(), stats.averageScore(), stats.playsCount(),
                        stats.reviewsCount(), stats.commentsCount(), card.tmdbVoteAverage());
        return new ContentChildCardDTO(
                card == null ? null : card.contentId(),
                coordinate.type(),
                coordinate.tmdbId(),
                coordinate.seriesTmdbId(),
                coordinate.seasonNumber(),
                spec.airedEpisodeCount(),
                coordinate.episodeNumber(),
                spec.title(),
                spec.overview(),
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
            String overview,
            String posterPath,
            LocalDate releaseDate,
            Integer runtimeMinutes,
            Integer airedEpisodeCount,
            Double tmdbVoteAverage,
            boolean tmdbVoteAverageResolved) {
    }

    private record SectionSpecs(
            List<CardSpec> seasons,
            List<CardSpec> episodes,
            List<CardSpec> recentEpisodes) {
    }

    private record EpisodeBoundary(List<EpisodeSummaryDTO> episodes, Integer episodeCount) {
    }
}
