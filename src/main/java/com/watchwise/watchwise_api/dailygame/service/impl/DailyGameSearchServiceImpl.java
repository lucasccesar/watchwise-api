package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbCardMetadata;
import com.watchwise.watchwise_api.common.tmdb.TmdbCardMetadataKey;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.TmdbSeriesHierarchyService;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameEpisodeOptionDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameSeasonOptionDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameSearchResultDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.service.DailyGameSearchService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;

@Service
public class DailyGameSearchServiceImpl implements DailyGameSearchService {

    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_SEARCH_PAGE_SIZE = 100;
    private static final int MAX_VISUAL_SEARCH_PAGE_SIZE = 6;
    private static final int TMDB_SEARCH_PAGE_SIZE = 20;
    private static final String LANGUAGE = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;

    private final TmdbClient tmdbClient;
    private final PageRequestFactory pageRequestFactory;
    private final TmdbSeriesHierarchyService seriesHierarchyService;

    public DailyGameSearchServiceImpl(
            TmdbClient tmdbClient,
            PageRequestFactory pageRequestFactory,
            TmdbSeriesHierarchyService seriesHierarchyService) {
        this.tmdbClient = tmdbClient;
        this.pageRequestFactory = pageRequestFactory;
        this.seriesHierarchyService = seriesHierarchyService;
    }

    @Override
    public Page<DailyGameSearchResultDTO> search(
            UUID userId, DailyGameType type, String query, Integer page, Integer size) {
        return search(userId, type, query, page, size, false);
    }

    @Override
    public Page<DailyGameSearchResultDTO> search(
            UUID userId, DailyGameType type, String query, Integer page, Integer size, boolean view) {
        String normalizedQuery = normalizeQuery(query);
        PageRequest pageRequest = pageRequestFactory.build(page, size,
                view ? MAX_VISUAL_SEARCH_PAGE_SIZE : MAX_SEARCH_PAGE_SIZE);
        if (type == null) {
            throw new BadRequestException("A daily game type is required");
        }
        if (view && type != DailyGameType.MOVIE_BY_POSTER
                && type != DailyGameType.SERIES_BY_POSTER
                && type != DailyGameType.PERSON_BY_FACE
                && type != DailyGameType.EPISODE_BY_FRAME) {
            throw new BadRequestException("Unsupported visual daily game type");
        }
        if (type == DailyGameType.EPISODE_BY_FRAME) {
            throw new BadRequestException("Episode searches require the episode search endpoints");
        }

        Page<DailyGameSearchResultDTO> results = switch (type.targetKind()) {
            case MOVIE -> searchMovies(normalizedQuery, pageRequest);
            case SERIES -> searchSeries(normalizedQuery, pageRequest);
            case PERSON -> searchPeople(normalizedQuery, pageRequest);
            case EPISODE -> throw new BadRequestException("Episode searches require the episode search endpoints");
        };
        return view && type.targetKind() != DailyGameTargetKind.PERSON ? enrich(results) : results;
    }

    @Override
    public Page<DailyGameSearchResultDTO> searchEpisodeSeries(
            UUID userId, String query, Integer page, Integer size) {
        return searchEpisodeSeries(userId, query, page, size, false);
    }

    @Override
    public Page<DailyGameSearchResultDTO> searchEpisodeSeries(
            UUID userId, String query, Integer page, Integer size, boolean view) {
        String normalizedQuery = normalizeQuery(query);
        PageRequest pageRequest = pageRequestFactory.build(page, size,
                view ? MAX_VISUAL_SEARCH_PAGE_SIZE : MAX_SEARCH_PAGE_SIZE);
        Page<DailyGameSearchResultDTO> results = searchSeries(normalizedQuery, pageRequest);
        return view ? enrich(results) : results;
    }

    @Override
    public List<DailyGameSeasonOptionDTO> listEpisodeSeasons(UUID userId, String seriesTmdbId) {
        String normalizedSeriesTmdbId = normalizePositiveIdentifier(seriesTmdbId);
        return seriesHierarchyService.listSeasons(normalizedSeriesTmdbId, false, LANGUAGE).stream()
                .map(season -> new DailyGameSeasonOptionDTO(
                        season.seasonNumber(), season.name(), season.episodeCount()))
                .toList();
    }

    @Override
    public List<DailyGameEpisodeOptionDTO> listEpisodeEpisodes(
            UUID userId, String seriesTmdbId, Integer seasonNumber) {
        String normalizedSeriesTmdbId = normalizePositiveIdentifier(seriesTmdbId);
        int normalizedSeasonNumber = normalizePositiveSeasonNumber(seasonNumber);
        return seriesHierarchyService.listEpisodes(
                        normalizedSeriesTmdbId, normalizedSeasonNumber, false, LANGUAGE).stream()
                .map(episode -> new DailyGameEpisodeOptionDTO(
                        episode.seriesTmdbId(), episode.seasonNumber(), episode.episodeNumber(),
                        episode.name(), episode.airDate()))
                .toList();
    }

    private Page<DailyGameSearchResultDTO> searchMovies(String query, PageRequest pageRequest) {
        return searchExternal(
                pageRequest,
                remotePage -> tmdbClient.searchMovies(query, LANGUAGE, remotePage),
                movie -> new DailyGameSearchResultDTO(
                        DailyGameTargetKind.MOVIE,
                        movie.id(),
                        null,
                        null,
                        null,
                        null,
                        movie.title(),
                        TmdbImageUrlBuilder.posterUrl(movie.posterPath()),
                        parseDate(movie.releaseDate())));
    }

    private Page<DailyGameSearchResultDTO> searchSeries(String query, PageRequest pageRequest) {
        return searchExternal(
                pageRequest,
                remotePage -> tmdbClient.searchTv(query, LANGUAGE, remotePage),
                series -> new DailyGameSearchResultDTO(
                        DailyGameTargetKind.SERIES,
                        series.id(),
                        null,
                        null,
                        null,
                        null,
                        series.name(),
                        TmdbImageUrlBuilder.posterUrl(series.posterPath()),
                        parseDate(series.firstAirDate())));
    }

    private Page<DailyGameSearchResultDTO> searchPeople(String query, PageRequest pageRequest) {
        return searchExternal(
                pageRequest,
                remotePage -> tmdbClient.searchPeople(query, LANGUAGE, remotePage),
                person -> new DailyGameSearchResultDTO(
                        DailyGameTargetKind.PERSON,
                        null,
                        person.id(),
                        null,
                        null,
                        null,
                        person.name(),
                        TmdbImageUrlBuilder.profileUrl(person.profilePath()),
                        null));
    }

    private <T> TmdbSearchPage<T> searchPageOrEmpty(
            TmdbLookupResult<TmdbSearchPage<T>> lookup, int requestedPage) {
        if (lookup == null || lookup.isUnavailable()) {
            throw tmdbUnavailable();
        }
        if (!(lookup instanceof TmdbLookupResult.Found<TmdbSearchPage<T>> found)
                || found.value() == null) {
            return new TmdbSearchPage<>(requestedPage, List.of(), 0, 0);
        }
        TmdbSearchPage<T> page = found.value();
        return new TmdbSearchPage<>(
                page.page(),
                page.results() == null ? List.of() : page.results(),
                page.totalPages(),
                page.totalResults());
    }

    private <T> Page<DailyGameSearchResultDTO> searchExternal(
            PageRequest pageRequest,
            IntFunction<TmdbLookupResult<TmdbSearchPage<T>>> remoteSearch,
            Function<T, DailyGameSearchResultDTO> mapper) {
        long start = pageRequest.getOffset();
        long end = start + pageRequest.getPageSize() - 1L;
        int firstRemotePage = remotePageFor(start);
        TmdbSearchPage<T> firstPage = searchPageOrEmpty(remoteSearch.apply(firstRemotePage), firstRemotePage);
        long totalResults = firstPage.totalResults();

        if (start >= totalResults) {
            return new PageImpl<>(List.of(), pageRequest, totalResults);
        }

        long effectiveEnd = Math.min(end, totalResults - 1);
        int lastRemotePage = remotePageFor(effectiveEnd);
        List<T> remoteResults = new ArrayList<>();
        remoteResults.addAll(firstPage.results());
        for (long remotePage = (long) firstRemotePage + 1; remotePage <= lastRemotePage; remotePage++) {
            int requestedRemotePage = (int) remotePage;
            remoteResults.addAll(searchPageOrEmpty(
                    remoteSearch.apply(requestedRemotePage), requestedRemotePage).results());
        }

        int firstLocalIndex = (int) (start - (long) (firstRemotePage - 1) * TMDB_SEARCH_PAGE_SIZE);
        int fromIndex = Math.min(firstLocalIndex, remoteResults.size());
        int toIndex = Math.min(fromIndex + pageRequest.getPageSize(), remoteResults.size());
        List<DailyGameSearchResultDTO> content = remoteResults.subList(fromIndex, toIndex).stream()
                .map(mapper)
                .toList();
        return new PageImpl<>(content, pageRequest, totalResults);
    }

    private int remotePageFor(long resultIndex) {
        long page = resultIndex / TMDB_SEARCH_PAGE_SIZE + 1;
        if (page > Integer.MAX_VALUE) {
            throw new BadRequestException("Page number is too large for external search");
        }
        return (int) page;
    }

    private Page<DailyGameSearchResultDTO> enrich(Page<DailyGameSearchResultDTO> results) {
        Map<String, TmdbCardMetadata> metadataById = new LinkedHashMap<>();
        for (DailyGameSearchResultDTO result : results.getContent()) {
            if (result.tmdbId() != null && !metadataById.containsKey(result.tmdbId())) {
                try {
                    ContentCoordinate coordinate = new ContentCoordinate(
                            result.targetKind() == DailyGameTargetKind.MOVIE ? ContentType.MOVIE : ContentType.SERIES,
                            result.tmdbId(), null, null, null);
                    TmdbCardMetadataKey.Type type = result.targetKind() == DailyGameTargetKind.MOVIE
                            ? TmdbCardMetadataKey.Type.MOVIE : TmdbCardMetadataKey.Type.SERIES;
                    TmdbLookupResult<TmdbCardMetadata> lookup = tmdbClient.getCardMetadata(
                            new TmdbCardMetadataKey(type, coordinate.tmdbId(), null, null, null), LANGUAGE);
                    if (lookup instanceof TmdbLookupResult.Found<TmdbCardMetadata> found
                            && found.value() != null) {
                        metadataById.put(result.tmdbId(), found.value());
                    } else {
                        metadataById.put(result.tmdbId(), null);
                    }
                } catch (RuntimeException ignored) {
                    metadataById.put(result.tmdbId(), null);
                }
            }
        }
        List<DailyGameSearchResultDTO> enriched = results.getContent().stream().map(result -> {
            TmdbCardMetadata metadata = metadataById.get(result.tmdbId());
            return new DailyGameSearchResultDTO(result.targetKind(), result.tmdbId(), result.personTmdbId(),
                    result.seriesTmdbId(), result.seasonNumber(), result.episodeNumber(), result.title(),
                    result.imageUrl(), result.date(), metadata == null ? null : metadata.genres(),
                    metadata == null ? null : metadata.numberOfSeasons());
        }).toList();
        return new PageImpl<>(enriched, results.getPageable(), results.getTotalElements());
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new BadRequestException("q must be provided");
        }
        String trimmedQuery = query.trim();
        if (trimmedQuery.isEmpty()) {
            throw new BadRequestException("q must be provided");
        }
        if (trimmedQuery.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("q must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        return trimmedQuery;
    }

    private String normalizePositiveIdentifier(String value) {
        String trimmedValue = value == null ? null : value.trim();
        if (trimmedValue == null || !trimmedValue.matches("[1-9]\\d*")) {
            throw new BadRequestException("seriesTmdbId must be a positive numeric identifier");
        }
        return trimmedValue;
    }

    private int normalizePositiveSeasonNumber(Integer value) {
        if (value == null || value <= 0) {
            throw new BadRequestException("seasonNumber must be a positive integer");
        }
        return value;
    }

    private TmdbUnavailableException tmdbUnavailable() {
        return new TmdbUnavailableException("TMDB is currently unavailable");
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
