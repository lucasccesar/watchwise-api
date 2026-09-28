package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
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
import com.watchwise.watchwise_api.dailygame.dto.DailyGameSearchResultDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.service.DailyGameSearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class DailyGameSearchServiceImpl implements DailyGameSearchService {

    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_SEARCH_PAGE_SIZE = 100;
    private static final String THROTTLE_KEY_PREFIX = "daily-game-search|";
    private static final String LANGUAGE = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;

    private final TmdbClient tmdbClient;
    private final PageRequestFactory pageRequestFactory;
    private final RequestThrottler requestThrottler;
    private final Clock clock;

    @Value("${app.rate-limit.daily-game-search.max-requests:30}")
    private int searchMaxRequests = 30;

    @Value("${app.rate-limit.daily-game-search.window-minutes:5}")
    private long searchWindowMinutes = 5;

    public DailyGameSearchServiceImpl(
            TmdbClient tmdbClient,
            PageRequestFactory pageRequestFactory,
            RequestThrottler requestThrottler,
            Clock clock) {
        this.tmdbClient = tmdbClient;
        this.pageRequestFactory = pageRequestFactory;
        this.requestThrottler = requestThrottler;
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

        throttle(userId);
        int tmdbPage = pageRequest.getPageNumber() + 1;
        return switch (type.targetKind()) {
            case MOVIE -> searchMovies(normalizedQuery, tmdbPage, pageRequest);
            case SERIES -> searchSeries(normalizedQuery, tmdbPage, pageRequest);
            case PERSON -> searchPeople(normalizedQuery, tmdbPage, pageRequest);
            case EPISODE -> throw new BadRequestException("Episode searches require the episode search endpoints");
        };
    }

    @Override
    public Page<DailyGameSearchResultDTO> searchEpisodeSeries(
            UUID userId, String query, Integer page, Integer size) {
        String normalizedQuery = normalizeQuery(query);
        PageRequest pageRequest = buildPageRequest(page, size);
        throttle(userId);
        return searchSeries(normalizedQuery, pageRequest.getPageNumber() + 1, pageRequest);
    }

    @Override
    public Page<DailyGameSearchResultDTO> searchEpisodes(
            UUID userId, String seriesTmdbId, String query, Integer page, Integer size) {
        String normalizedSeriesTmdbId = normalizePositiveIdentifier(seriesTmdbId);
        String normalizedQuery = normalizeQuery(query);
        PageRequest pageRequest = buildPageRequest(page, size);
        throttle(userId);

        TmdbTvFullDetails series = foundValueOrEmpty(
                tmdbClient.getTvFullDetails(normalizedSeriesTmdbId, LANGUAGE));
        if (series == null || series.seasons() == null) {
            return new PageImpl<>(List.of(), pageRequest, 0);
        }

        LocalDate today = LocalDate.now(clock);
        String queryLowerCase = normalizedQuery.toLowerCase(Locale.ROOT);
        List<DailyGameSearchResultDTO> candidates = series.seasons().stream()
                .filter(this::isRegularSeason)
                .flatMap(season -> seasonCandidates(
                        normalizedSeriesTmdbId, season.seasonNumber(), queryLowerCase, today).stream())
                .toList();

        int fromIndex = Math.min((int) pageRequest.getOffset(), candidates.size());
        int toIndex = Math.min(fromIndex + pageRequest.getPageSize(), candidates.size());
        return new PageImpl<>(candidates.subList(fromIndex, toIndex), pageRequest, candidates.size());
    }

    private Page<DailyGameSearchResultDTO> searchMovies(String query, int page, PageRequest pageRequest) {
        TmdbSearchPage<TmdbMovieSearchResult> searchPage = searchPageOrEmpty(
                tmdbClient.searchMovies(query, LANGUAGE, page), page);
        List<DailyGameSearchResultDTO> candidates = searchPage.results().stream()
                .map(movie -> new DailyGameSearchResultDTO(
                        DailyGameTargetKind.MOVIE,
                        movie.id(),
                        null,
                        null,
                        null,
                        null,
                        movie.title(),
                        TmdbImageUrlBuilder.posterUrl(movie.posterPath()),
                        parseDate(movie.releaseDate())))
                .toList();
        return externalPage(searchPage, pageRequest, candidates);
    }

    private Page<DailyGameSearchResultDTO> searchSeries(String query, int page, PageRequest pageRequest) {
        TmdbSearchPage<TmdbTvSearchResult> searchPage = searchPageOrEmpty(
                tmdbClient.searchTv(query, LANGUAGE, page), page);
        List<DailyGameSearchResultDTO> candidates = searchPage.results().stream()
                .map(series -> new DailyGameSearchResultDTO(
                        DailyGameTargetKind.SERIES,
                        series.id(),
                        null,
                        null,
                        null,
                        null,
                        series.name(),
                        TmdbImageUrlBuilder.posterUrl(series.posterPath()),
                        parseDate(series.firstAirDate())))
                .toList();
        return externalPage(searchPage, pageRequest, candidates);
    }

    private Page<DailyGameSearchResultDTO> searchPeople(String query, int page, PageRequest pageRequest) {
        TmdbSearchPage<TmdbPersonSearchResult> searchPage = searchPageOrEmpty(
                tmdbClient.searchPeople(query, LANGUAGE, page), page);
        List<DailyGameSearchResultDTO> candidates = searchPage.results().stream()
                .map(person -> new DailyGameSearchResultDTO(
                        DailyGameTargetKind.PERSON,
                        null,
                        person.id(),
                        null,
                        null,
                        null,
                        person.name(),
                        TmdbImageUrlBuilder.profileUrl(person.profilePath()),
                        null))
                .toList();
        return externalPage(searchPage, pageRequest, candidates);
    }

    private List<DailyGameSearchResultDTO> seasonCandidates(
            String seriesTmdbId, Integer seasonNumber, String query, LocalDate today) {
        TmdbLookupResult<TmdbSeasonFullDetails> lookup = tmdbClient.getSeasonFullDetails(
                seriesTmdbId, seasonNumber, LANGUAGE);
        if (lookup == null || lookup.isUnavailable()) {
            throw tmdbUnavailable();
        }
        if (!(lookup instanceof TmdbLookupResult.Found<TmdbSeasonFullDetails> found)
                || found.value() == null || found.value().episodes() == null) {
            return List.of();
        }
        return found.value().episodes().stream()
                .filter(episode -> isReleasedMatchingEpisode(episode, query, today))
                .map(episode -> toEpisodeResult(seriesTmdbId, seasonNumber, episode))
                .toList();
    }

    private TmdbTvFullDetails foundValueOrEmpty(TmdbLookupResult<TmdbTvFullDetails> lookup) {
        if (lookup == null || lookup.isUnavailable()) {
            throw tmdbUnavailable();
        }
        if (lookup instanceof TmdbLookupResult.Found<TmdbTvFullDetails> found) {
            return found.value();
        }
        return null;
    }

    private boolean isRegularSeason(TmdbSeasonSummary season) {
        return season != null && season.seasonNumber() != null && season.seasonNumber() > 0;
    }

    private boolean isReleasedMatchingEpisode(TmdbEpisodeSummary episode, String query, LocalDate today) {
        if (episode == null || episode.episodeNumber() == null || episode.episodeNumber() < 1
                || episode.name() == null || episode.name().isBlank()
                || episode.stillPath() == null || episode.stillPath().isBlank()) {
            return false;
        }
        LocalDate airDate = parseDate(episode.airDate());
        return airDate != null && !airDate.isAfter(today)
                && episode.name().toLowerCase(Locale.ROOT).contains(query);
    }

    private DailyGameSearchResultDTO toEpisodeResult(
            String seriesTmdbId, Integer seasonNumber, TmdbEpisodeSummary episode) {
        return new DailyGameSearchResultDTO(
                DailyGameTargetKind.EPISODE,
                null,
                null,
                seriesTmdbId,
                seasonNumber,
                episode.episodeNumber(),
                episode.name(),
                TmdbImageUrlBuilder.stillUrl(episode.stillPath()),
                parseDate(episode.airDate()));
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

    private Page<DailyGameSearchResultDTO> externalPage(
            TmdbSearchPage<?> searchPage, PageRequest pageRequest, List<DailyGameSearchResultDTO> candidates) {
        List<DailyGameSearchResultDTO> content = candidates.size() > pageRequest.getPageSize()
                ? candidates.subList(0, pageRequest.getPageSize())
                : candidates;
        return new PageImpl<>(content, pageRequest, searchPage.totalResults()) {
            @Override
            public long getTotalElements() {
                return searchPage.totalResults();
            }

            @Override
            public int getNumber() {
                return Math.max(0, searchPage.page() - 1);
            }

            @Override
            public int getTotalPages() {
                return searchPage.totalPages();
            }

            @Override
            public boolean hasNext() {
                return searchPage.page() < searchPage.totalPages();
            }

            @Override
            public boolean isLast() {
                return !hasNext();
            }
        };
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
        if (trimmedValue == null || !trimmedValue.matches("[1-9]\\d{0,19}")) {
            throw new BadRequestException("seriesTmdbId must be a positive numeric identifier");
        }
        return trimmedValue;
    }

    private void throttle(UUID userId) {
        requestThrottler.checkAllowed(
                THROTTLE_KEY_PREFIX + userId,
                searchMaxRequests,
                Duration.ofMinutes(searchWindowMinutes));
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
