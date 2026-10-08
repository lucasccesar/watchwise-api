package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbCardMetadata;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentCardStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
import com.watchwise.watchwise_api.content.service.ContentViewerStateService;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContentCardAssembler {

    private static final int MAX_STATS_BATCH_SIZE = 100;

    private final ContentStatsService contentStatsService;
    private final ContentViewerStateService contentViewerStateService;
    private final TmdbCardMetadataResolver tmdbCardMetadataResolver;
    private final UserContentPosterService userContentPosterService;

    public Map<ContentCoordinate, ContentCardDTO> assemble(
            Collection<ContentCardSpec> specs,
            ContentCardContext context,
            ContentCardFieldSet field) {
        return assemble(specs, context, field == null ? Set.of() : Set.of(field));
    }

    public Map<ContentCoordinate, ContentCardDTO> assemble(
            Collection<ContentCardSpec> specs,
            ContentCardContext context,
            Set<ContentCardFieldSet> fields) {
        return assembleWithViewerStates(specs, context, fields).cardsByCoordinate();
    }

    AssemblyResult assembleWithViewerStates(
            Collection<ContentCardSpec> specs,
            ContentCardContext context,
            Set<ContentCardFieldSet> fields) {
        Objects.requireNonNull(context, "context is required");
        List<ContentCardSpec> distinctSpecs = distinctSpecs(specs);
        if (distinctSpecs.isEmpty()) {
            return new AssemblyResult(Map.of(), Map.of());
        }

        Set<ContentCardFieldSet> requestedFields = normalizedFields(fields);
        boolean viewerStateRequested = requestedFields.contains(ContentCardFieldSet.VIEWER_STATE)
                || requestedFields.contains(ContentCardFieldSet.WATCHLIST_PROGRESS);
        UUID viewerId = viewerStateRequested ? context.viewerId() : null;
        List<ContentCoordinate> coordinates = distinctSpecs.stream()
                .map(ContentCardSpec::coordinate)
                .toList();
        ContentViewerStateService.Resolution resolution = contentViewerStateService.resolve(
                viewerId, coordinates, Map.of());
        List<UUID> existingContentIds = coordinates.stream()
                .map(resolution.existingContentIdsByCoordinate()::get)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<UUID, ContentStatsResponseDTO> statsByContentId = requestedFields.contains(ContentCardFieldSet.STATS)
                ? statsByContentId(existingContentIds)
                : Map.of();
        Map<UUID, String> customPosterByContentId = requestedFields.contains(ContentCardFieldSet.SOCIAL_METADATA)
                && context.posterUserId() != null
                && !existingContentIds.isEmpty()
                ? userContentPosterService.findByUserAndContentIds(
                        context.posterUserId(), existingContentIds)
                : Map.of();

        Map<ContentCoordinate, CardData> cardDataByCoordinate = distinctSpecs.stream()
                .collect(Collectors.toMap(
                        ContentCardSpec::coordinate,
                        spec -> new CardData(
                                spec,
                                metadata(spec, context, requestedFields),
                                resolution.existingContentIdsByCoordinate().get(spec.coordinate()),
                                resolution.statesByCoordinate().get(spec.coordinate()),
                                statsByContentId,
                                customPosterByContentId),
                        (left, right) -> left,
                        LinkedHashMap::new));

        Map<ContentCoordinate, ContentCardDTO> cards = cardDataByCoordinate.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> toCard(entry.getValue(), requestedFields),
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<ContentCoordinate, ContentViewerStateDTO> fullViewerStates = viewerStateRequested
                ? new LinkedHashMap<>(resolution.statesByCoordinate())
                : Map.of();
        return new AssemblyResult(cards, fullViewerStates);
    }

    private List<ContentCardSpec> distinctSpecs(Collection<ContentCardSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        return specs.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        ContentCardSpec::coordinate,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new))
                .values().stream()
                .toList();
    }

    private Set<ContentCardFieldSet> normalizedFields(Set<ContentCardFieldSet> fields) {
        if (fields == null || fields.isEmpty()) {
            return Set.of();
        }
        return fields.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }

    private Map<UUID, ContentStatsResponseDTO> statsByContentId(Collection<UUID> contentIds) {
        List<UUID> distinctContentIds = contentIds == null
                ? List.of()
                : contentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctContentIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, ContentStatsResponseDTO> statsByContentId = new LinkedHashMap<>();
        for (int start = 0; start < distinctContentIds.size(); start += MAX_STATS_BATCH_SIZE) {
            int end = Math.min(start + MAX_STATS_BATCH_SIZE, distinctContentIds.size());
            List<ContentStatsResponseDTO> stats = contentStatsService.getStatsBatch(
                    distinctContentIds.subList(start, end));
            if (stats == null) {
                continue;
            }
            stats.stream()
                    .filter(Objects::nonNull)
                    .filter(stat -> stat.contentId() != null)
                    .forEach(stat -> statsByContentId.putIfAbsent(stat.contentId(), stat));
        }
        return statsByContentId;
    }

    private CardMetadata metadata(
            ContentCardSpec spec,
            ContentCardContext context,
            Set<ContentCardFieldSet> requestedFields) {
        if (!requestedFields.contains(ContentCardFieldSet.BASIC_METADATA)
                || hasAllKnownMetadata(spec)) {
            return new CardMetadata(null, ContentPreviewStatus.AVAILABLE);
        }

        TmdbLookupResult<TmdbCardMetadata> lookup = tmdbCardMetadataResolver.resolve(
                spec.coordinate(), context.language());
        if (lookup instanceof TmdbLookupResult.Found<TmdbCardMetadata> found) {
            TmdbCardMetadata value = found.value();
            return new CardMetadata(value, metadataStatus(spec, value));
        }
        return new CardMetadata(null, hasKnownMetadata(spec)
                ? ContentPreviewStatus.PARTIAL
                : ContentPreviewStatus.UNAVAILABLE);
    }

    private boolean hasAllKnownMetadata(ContentCardSpec spec) {
        return spec.title() != null
                && spec.posterPath() != null
                && spec.releaseDate() != null
                && (spec.runtimeMinutes() != null || spec.coordinate().type() == ContentType.SEASON);
    }

    private boolean hasKnownMetadata(ContentCardSpec spec) {
        return spec.title() != null
                || spec.posterPath() != null
                || spec.releaseDate() != null
                || spec.runtimeMinutes() != null;
    }

    private ContentPreviewStatus metadataStatus(ContentCardSpec spec, TmdbCardMetadata metadata) {
        if (metadata == null) {
            return hasKnownMetadata(spec) ? ContentPreviewStatus.PARTIAL : ContentPreviewStatus.UNAVAILABLE;
        }
        boolean hasTitle = spec.title() != null || metadata.title() != null;
        boolean hasPoster = spec.posterPath() != null || metadata.posterPath() != null;
        boolean hasReleaseDate = spec.releaseDate() != null || parseDate(metadata.releaseDate()) != null;
        boolean hasRuntime = spec.runtimeMinutes() != null || metadata.runtimeMinutes() != null;
        boolean runtimeOptional = spec.coordinate().type() == ContentType.SEASON;
        return hasTitle && hasPoster && hasReleaseDate && (hasRuntime || runtimeOptional)
                ? ContentPreviewStatus.AVAILABLE
                : ContentPreviewStatus.PARTIAL;
    }

    private ContentCardDTO toCard(CardData data, Set<ContentCardFieldSet> requestedFields) {
        ContentCardSpec spec = data.spec();
        ContentCoordinate coordinate = spec.coordinate();
        TmdbCardMetadata metadata = data.metadata().metadata();
        String title = spec.title() != null ? spec.title() : metadataValue(metadata, TmdbCardMetadata::title);
        String posterPath = spec.posterPath() != null
                ? spec.posterPath()
                : metadataValue(metadata, TmdbCardMetadata::posterPath);
        LocalDate releaseDate = spec.releaseDate() != null
                ? spec.releaseDate()
                : parseDate(metadataValue(metadata, TmdbCardMetadata::releaseDate));
        Integer runtimeMinutes = spec.runtimeMinutes() != null
                ? spec.runtimeMinutes()
                : metadata == null ? null : metadata.runtimeMinutes();
        Integer numberOfSeasons = metadata == null ? null : metadata.numberOfSeasons();
        List<String> genres = metadata == null ? null : metadata.genres();
        ContentCardStatsDTO stats = requestedFields.contains(ContentCardFieldSet.STATS)
                ? toStats(data.contentId(), data.contentId() == null
                        ? null
                        : data.statsByContentId().get(data.contentId()))
                : null;
        ContentCardViewerStateDTO viewerState = requestedFields.contains(ContentCardFieldSet.VIEWER_STATE)
                || requestedFields.contains(ContentCardFieldSet.WATCHLIST_PROGRESS)
                ? toViewerState(data.resolutionState(), requestedFields)
                : null;
        String customPosterUrl = requestedFields.contains(ContentCardFieldSet.SOCIAL_METADATA)
                ? data.contentId() == null ? null : data.customPosterByContentId().get(data.contentId())
                : null;
        return new ContentCardDTO(
                data.contentId(),
                coordinate.type(),
                coordinate.tmdbId(),
                coordinate.seriesTmdbId(),
                coordinate.seasonNumber(),
                coordinate.episodeNumber(),
                title,
                posterPath,
                customPosterUrl,
                releaseDate,
                releaseDate == null ? null : releaseDate.getYear(),
                runtimeMinutes,
                null,
                numberOfSeasons,
                null,
                genres,
                stats,
                viewerState,
                data.metadata().status());
    }

    private ContentCardStatsDTO toStats(UUID contentId, ContentStatsResponseDTO stats) {
        if (stats == null) {
            return new ContentCardStatsDTO(null, 0L, 0L, 0L);
        }
        return new ContentCardStatsDTO(
                stats.averageScore(), stats.playsCount(), stats.reviewsCount(), stats.commentsCount());
    }

    private ContentCardViewerStateDTO toViewerState(
            ContentViewerStateDTO state, Set<ContentCardFieldSet> requestedFields) {
        if (state == null) {
            return null;
        }
        boolean includeViewerState = requestedFields.contains(ContentCardFieldSet.VIEWER_STATE);
        boolean includeWatchlistProgress = requestedFields.contains(ContentCardFieldSet.WATCHLIST_PROGRESS);
        return new ContentCardViewerStateDTO(
                includeViewerState || includeWatchlistProgress ? state.watchStatus() : null,
                includeViewerState ? state.myRating() : null,
                includeViewerState ? state.lastWatchedDate() : null,
                includeViewerState ? state.lastWatchNumber() : null,
                includeViewerState ? state.watchedInTheater() : null,
                includeViewerState ? state.playsCount() : null,
                includeWatchlistProgress ? state.inWatchlist() : null,
                includeWatchlistProgress ? state.dropped() : null,
                includeWatchlistProgress ? state.watchedEpisodeCount() : null,
                includeWatchlistProgress ? state.releasedEpisodeCount() : null);
    }

    private String metadataValue(TmdbCardMetadata metadata, Function<TmdbCardMetadata, String> getter) {
        return metadata == null ? null : getter.apply(metadata);
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    record AssemblyResult(
            Map<ContentCoordinate, ContentCardDTO> cardsByCoordinate,
            Map<ContentCoordinate, ContentViewerStateDTO> viewerStatesByCoordinate) {
    }

    private record CardData(
            ContentCardSpec spec,
            CardMetadata metadata,
            UUID contentId,
            ContentViewerStateDTO resolutionState,
            Map<UUID, ContentStatsResponseDTO> statsByContentId,
            Map<UUID, String> customPosterByContentId) {
    }

    private record CardMetadata(TmdbCardMetadata metadata, ContentPreviewStatus status) {
    }
}
