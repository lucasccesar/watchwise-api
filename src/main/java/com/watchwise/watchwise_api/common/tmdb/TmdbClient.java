package com.watchwise.watchwise_api.common.tmdb;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class TmdbClient {

    public static final String LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE = "en-US";

    private final RestClient tmdbRestClient;
    private final Cache<String, TmdbLookupResult<TmdbMovieFullDetails>> tmdbMovieFullDetailsCache;
    private final Cache<String, TmdbLookupResult<TmdbTvFullDetails>> tmdbTvFullDetailsCache;
    private final Cache<String, TmdbLookupResult<TmdbSeasonFullDetails>> tmdbSeasonFullDetailsCache;
    private final Cache<String, TmdbLookupResult<TmdbEpisodeFullDetails>> tmdbEpisodeFullDetailsCache;
    private final Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>>> tmdbMovieSearchCache;
    private final Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbTvSearchResult>>> tmdbTvSearchCache;
    private final Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbPersonSearchResult>>> tmdbPersonSearchCache;
    private final Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbMultiSearchResult>>> tmdbMultiSearchCache;

    public TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>> searchMovies(
            String query, String language, int page) {
        String trimmedQuery = query.trim();
        TmdbSearchCacheKey key = TmdbSearchCacheKey.of(trimmedQuery, TmdbSearchType.MOVIE, language, page);
        return cachedLookup(tmdbMovieSearchCache, key, () -> callWithRetry(() -> tmdbRestClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/search/movie")
                                .queryParam("query", "{query}")
                                .queryParam("language", language)
                                .queryParam("page", page)
                                .queryParam("include_adult", false)
                                .build(trimmedQuery))
                        .retrieve()
                        .body(new ParameterizedTypeReference<TmdbSearchPage<TmdbMovieSearchResult>>() {}),
                "movie search"));
    }

    public TmdbLookupResult<TmdbSearchPage<TmdbTvSearchResult>> searchTv(
            String query, String language, int page) {
        String trimmedQuery = query.trim();
        TmdbSearchCacheKey key = TmdbSearchCacheKey.of(trimmedQuery, TmdbSearchType.TV, language, page);
        return cachedLookup(tmdbTvSearchCache, key, () -> callWithRetry(() -> tmdbRestClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/search/tv")
                                .queryParam("query", "{query}")
                                .queryParam("language", language)
                                .queryParam("page", page)
                                .queryParam("include_adult", false)
                                .build(trimmedQuery))
                        .retrieve()
                        .body(new ParameterizedTypeReference<TmdbSearchPage<TmdbTvSearchResult>>() {}),
                "tv search"));
    }

    public TmdbLookupResult<TmdbSearchPage<TmdbPersonSearchResult>> searchPeople(
            String query, String language, int page) {
        String trimmedQuery = query.trim();
        TmdbSearchCacheKey key = TmdbSearchCacheKey.of(trimmedQuery, TmdbSearchType.PERSON, language, page);
        return cachedLookup(tmdbPersonSearchCache, key, () -> callWithRetry(() -> tmdbRestClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/search/person")
                                .queryParam("query", "{query}")
                                .queryParam("language", language)
                                .queryParam("page", page)
                                .queryParam("include_adult", false)
                                .build(trimmedQuery))
                        .retrieve()
                        .body(new ParameterizedTypeReference<TmdbSearchPage<TmdbPersonSearchResult>>() {}),
                "person search"));
    }

    public TmdbLookupResult<TmdbSearchPage<TmdbMultiSearchResult>> searchMulti(
            String query, String language, int page) {
        String trimmedQuery = query.trim();
        TmdbSearchCacheKey key = TmdbSearchCacheKey.of(trimmedQuery, TmdbSearchType.MULTI, language, page);
        return cachedLookup(tmdbMultiSearchCache, key, () -> callWithRetry(() -> tmdbRestClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/search/multi")
                                .queryParam("query", "{query}")
                                .queryParam("language", language)
                                .queryParam("page", page)
                                .queryParam("include_adult", false)
                                .build(trimmedQuery))
                        .retrieve()
                        .body(new ParameterizedTypeReference<TmdbSearchPage<TmdbMultiSearchResult>>() {}),
                "multi search"));
    }

    public Optional<TmdbMovieDetails> getMovieDetails(String tmdbId) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri("/movie/{id}", tmdbId)
                        .retrieve()
                        .body(TmdbMovieDetails.class),
                "movie " + tmdbId).toOptional();
    }

    public Optional<TmdbTvDetails> getTvDetails(String tmdbId) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri("/tv/{id}", tmdbId)
                        .retrieve()
                        .body(TmdbTvDetails.class),
                "tv " + tmdbId).toOptional();
    }

    public Optional<TmdbPersonCredits> getPersonCombinedCredits(String personTmdbId) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri("/person/{id}/combined_credits", personTmdbId)
                        .retrieve()
                        .body(TmdbPersonCredits.class),
                "person " + personTmdbId).toOptional();
    }

    public TmdbLookupResult<TmdbPersonDetails> getPersonDetails(String personTmdbId) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri("/person/{id}", personTmdbId)
                        .retrieve()
                        .body(TmdbPersonDetails.class),
                "person details " + personTmdbId);
    }

    public TmdbLookupResult<TmdbMovieFullDetails> getMovieFullDetails(String tmdbId, String language) {
        return cachedLookup(tmdbMovieFullDetailsCache, tmdbId + "|" + language, () -> callWithRetry(() -> tmdbRestClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/movie/{id}")
                                .queryParam("append_to_response", "credits,watch/providers,alternative_titles,videos")
                                .queryParam("language", language)
                                .build(tmdbId))
                        .retrieve()
                        .body(TmdbMovieFullDetails.class),
                "movie full details " + tmdbId));
    }

    public TmdbLookupResult<TmdbTvFullDetails> getTvFullDetails(String tmdbId, String language) {
        return cachedLookup(tmdbTvFullDetailsCache, tmdbId + "|" + language, () -> callWithRetry(() -> tmdbRestClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/tv/{id}")
                                .queryParam("append_to_response", "aggregate_credits,watch/providers,alternative_titles,videos")
                                .queryParam("language", language)
                                .build(tmdbId))
                        .retrieve()
                        .body(TmdbTvFullDetails.class),
                "tv full details " + tmdbId));
    }

    public TmdbLookupResult<TmdbSeasonFullDetails> getSeasonFullDetails(String seriesTmdbId, Integer seasonNumber, String language) {
        return cachedLookup(tmdbSeasonFullDetailsCache, seriesTmdbId + "|" + seasonNumber + "|" + language,
                () -> callWithRetry(() -> tmdbRestClient.get()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/tv/{seriesId}/season/{seasonNumber}")
                                        .queryParam("append_to_response", "aggregate_credits,watch/providers")
                                        .queryParam("language", language)
                                        .build(seriesTmdbId, seasonNumber))
                                .retrieve()
                                .body(TmdbSeasonFullDetails.class),
                        "season full details " + seriesTmdbId + "/" + seasonNumber));
    }

    public TmdbLookupResult<TmdbEpisodeFullDetails> getEpisodeFullDetails(
            String seriesTmdbId, Integer seasonNumber, Integer episodeNumber, String language) {
        return cachedLookup(tmdbEpisodeFullDetailsCache,
                seriesTmdbId + "|" + seasonNumber + "|" + episodeNumber + "|" + language,
                () -> callWithRetry(() -> tmdbRestClient.get()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/tv/{seriesId}/season/{seasonNumber}/episode/{episodeNumber}")
                                        .queryParam("language", language)
                                        .build(seriesTmdbId, seasonNumber, episodeNumber))
                                .retrieve()
                                .body(TmdbEpisodeFullDetails.class),
                        "episode full details " + seriesTmdbId + "/" + seasonNumber + "/" + episodeNumber));
    }

    private <K, T> TmdbLookupResult<T> cachedLookup(
            Cache<K, TmdbLookupResult<T>> cache, K key, Supplier<TmdbLookupResult<T>> loader) {
        TmdbLookupResult<T> cached = cache.get(key, ignoredKey -> {
            TmdbLookupResult<T> result = loader.get();
            return result.isUnavailable() ? null : result;
        });
        return cached != null ? cached : new TmdbLookupResult.Unavailable<>();
    }

    private <T> TmdbLookupResult<T> callWithRetry(Supplier<T> call, String description) {
        try {
            return attempt(call);
        } catch (HttpClientErrorException.NotFound notFound) {
            return new TmdbLookupResult.NotFound<>();
        } catch (RestClientException firstFailure) {
            log.warn("TMDB call failed for {}, retrying once: {}", description, firstFailure.getMessage());
            try {
                return attempt(call);
            } catch (HttpClientErrorException.NotFound notFound) {
                return new TmdbLookupResult.NotFound<>();
            } catch (RestClientException secondFailure) {
                log.warn("TMDB call failed for {} after retry, skipping this cycle: {}", description, secondFailure.getMessage());
                return new TmdbLookupResult.Unavailable<>();
            }
        }
    }

    private <T> TmdbLookupResult<T> attempt(Supplier<T> call) {
        T result = call.get();
        return result == null ? new TmdbLookupResult.NotFound<>() : new TmdbLookupResult.Found<>(result);
    }
}
