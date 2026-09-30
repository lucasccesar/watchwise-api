package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;

@Service
public class DailyGameSearchServiceImpl implements DailyGameSearchService {

    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_SEARCH_PAGE_SIZE = 100;
    private static final int TMDB_SEARCH_PAGE_SIZE = 20;
    private static final String LANGUAGE = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;

    private final TmdbClient tmdbClient;
    private final PageRequestFactory pageRequestFactory;
    private final Clock clock;

    public DailyGameSearchServiceImpl(
            TmdbClient tmdbClient,
            PageRequestFactory pageRequestFactory,
            Clock clock) {
        this.tmdbClient = tmdbClient;
        this.pageRequestFactory = pageRequestFactory;
        this.clock = clock;
    }

    @Override
    public Page<DailyGameSearchResultDTO> search(
            UUID userId, DailyGameType type, String query, Integer page, Integer size) {
        String normalizedQuery = normalizeQuery(query);
        PageRequest pageRequest = buildPageRequest(page, size);
        if (type == null) {
            throw new BadRequestException("A daily game type is required");
        }
        if (type == DailyGameType.EPISODE_BY_FRAME) {
            throw new BadRequestException("Episode searches require the episode search endpoints");
        }

        return switch (type.targetKind()) {
            case MOVIE -> searchMovies(normalizedQuery, pageRequest);
            case SERIES -> searchSeries(normalizedQuery, pageRequest);
            case PERSON -> searchPeople(normalizedQuery, pageRequest);
            case EPISODE -> throw new BadRequestException("Episode searches require the episode search endpoints");
        };
    }

    @Override
    public Page<DailyGameSearchResultDTO> searchEpisodeSeries(
            UUID userId, String query, Integer page, Integer size) {
        String normalizedQuery = normalizeQuery(query);
        PageRequest pageRequest = buildPageRequest(page, size);
        return searchSeries(normalizedQuery, pageRequest);
    }

    @Override
    public List<DailyGameSeasonOptionDTO> listEpisodeSeasons(UUID userId, String seriesTmdbId) {
        String normalizedSeriesTmdbId = normalizePositiveIdentifier(seriesTmdbId);
        TmdbTvFullDetails series = requireFound(
                tmdbClient.getTvFullDetails(normalizedSeriesTmdbId, LANGUAGE), "series");
        if (series.seasons() == null) {
            return List.of();
        }

        return series.seasons().stream()
                .filter(this::isRegularSeason)
                .map(season -> new DailyGameSeasonOptionDTO(
                        season.seasonNumber(), season.name(), season.episodeCount()))
                .toList();
    }

    @Override
    public List<DailyGameEpisodeOptionDTO> listEpisodeEpisodes(
            UUID userId, String seriesTmdbId, Integer seasonNumber) {
        String normalizedSeriesTmdbId = normalizePositiveIdentifier(seriesTmdbId);
        int normalizedSeasonNumber = normalizePositiveSeasonNumber(seasonNumber);
        TmdbLookupResult<TmdbSeasonFullDetails> lookup = tmdbClient.getSeasonFullDetails(
                normalizedSeriesTmdbId, normalizedSeasonNumber, LANGUAGE);
        if (lookup == null || lookup.isUnavailable()) {
            throw tmdbUnavailable();
        }
        TmdbSeasonFullDetails season = requireFound(lookup, "season");
        if (season.episodes() == null) {
            return List.of();
        }

        LocalDate today = LocalDate.now(clock);
        return season.episodes().stream()
                .filter(episode -> isReleasedEpisode(episode, today))
                .map(episode -> new DailyGameEpisodeOptionDTO(
                        normalizedSeriesTmdbId,
                        normalizedSeasonNumber,
                        episode.episodeNumber(),
                        episode.name(),
                        parseDate(episode.airDate())))
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

    private <T> T requireFound(TmdbLookupResult<T> lookup, String subject) {
        if (lookup == null || lookup.isUnavailable()) {
            throw tmdbUnavailable();
        }
        if (lookup instanceof TmdbLookupResult.NotFound<T>) {
            throw new NotFoundException("No " + subject + " found on TMDB for the given id");
        }
        if (lookup instanceof TmdbLookupResult.Found<T> found && found.value() != null) {
            return found.value();
        }
        throw tmdbUnavailable();
    }

    private boolean isRegularSeason(TmdbSeasonSummary season) {
        return season != null && season.seasonNumber() != null && season.seasonNumber() > 0;
    }

    private boolean isReleasedEpisode(TmdbEpisodeSummary episode, LocalDate today) {
        if (episode == null || episode.episodeNumber() == null || episode.episodeNumber() < 1
                || episode.name() == null || episode.name().isBlank()) {
            return false;
        }
        LocalDate airDate = parseDate(episode.airDate());
        return airDate != null && !airDate.isAfter(today);
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

    private PageRequest buildPageRequest(Integer page, Integer size) {
        return pageRequestFactory.build(page, size, MAX_SEARCH_PAGE_SIZE);
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
