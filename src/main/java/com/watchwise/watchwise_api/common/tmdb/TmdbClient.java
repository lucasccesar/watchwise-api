package com.watchwise.watchwise_api.common.tmdb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class TmdbClient {

    private final RestClient tmdbRestClient;

    public Optional<TmdbMovieDetails> getMovieDetails(String tmdbId) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri("/movie/{id}", tmdbId)
                        .retrieve()
                        .body(TmdbMovieDetails.class),
                "movie " + tmdbId);
    }

    public Optional<TmdbTvDetails> getTvDetails(String tmdbId) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri("/tv/{id}", tmdbId)
                        .retrieve()
                        .body(TmdbTvDetails.class),
                "tv " + tmdbId);
    }

    public Optional<TmdbPersonCredits> getPersonCombinedCredits(String personTmdbId) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri("/person/{id}/combined_credits", personTmdbId)
                        .retrieve()
                        .body(TmdbPersonCredits.class),
                "person " + personTmdbId);
    }

    @Cacheable(cacheNames = "tmdbMovieFullDetails", key = "#tmdbId + '|' + #language", unless = "#result.isEmpty()")
    public Optional<TmdbMovieFullDetails> getMovieFullDetails(String tmdbId, String language) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/movie/{id}")
                                .queryParam("append_to_response", "credits,watch/providers,alternative_titles")
                                .queryParam("language", language)
                                .build(tmdbId))
                        .retrieve()
                        .body(TmdbMovieFullDetails.class),
                "movie full details " + tmdbId);
    }

    @Cacheable(cacheNames = "tmdbTvFullDetails", key = "#tmdbId + '|' + #language", unless = "#result.isEmpty()")
    public Optional<TmdbTvFullDetails> getTvFullDetails(String tmdbId, String language) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/tv/{id}")
                                .queryParam("append_to_response", "aggregate_credits,watch/providers,alternative_titles")
                                .queryParam("language", language)
                                .build(tmdbId))
                        .retrieve()
                        .body(TmdbTvFullDetails.class),
                "tv full details " + tmdbId);
    }

    @Cacheable(cacheNames = "tmdbSeasonFullDetails", key = "#seriesTmdbId + '|' + #seasonNumber + '|' + #language", unless = "#result.isEmpty()")
    public Optional<TmdbSeasonFullDetails> getSeasonFullDetails(String seriesTmdbId, Integer seasonNumber, String language) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/tv/{seriesId}/season/{seasonNumber}")
                                .queryParam("append_to_response", "watch/providers")
                                .queryParam("language", language)
                                .build(seriesTmdbId, seasonNumber))
                        .retrieve()
                        .body(TmdbSeasonFullDetails.class),
                "season full details " + seriesTmdbId + "/" + seasonNumber);
    }

    @Cacheable(cacheNames = "tmdbEpisodeFullDetails", key = "#seriesTmdbId + '|' + #seasonNumber + '|' + #episodeNumber + '|' + #language", unless = "#result.isEmpty()")
    public Optional<TmdbEpisodeFullDetails> getEpisodeFullDetails(
            String seriesTmdbId, Integer seasonNumber, Integer episodeNumber, String language) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/tv/{seriesId}/season/{seasonNumber}/episode/{episodeNumber}")
                                .queryParam("language", language)
                                .build(seriesTmdbId, seasonNumber, episodeNumber))
                        .retrieve()
                        .body(TmdbEpisodeFullDetails.class),
                "episode full details " + seriesTmdbId + "/" + seasonNumber + "/" + episodeNumber);
    }

    private <T> Optional<T> callWithRetry(Supplier<T> call, String description) {
        try {
            return Optional.ofNullable(call.get());
        } catch (RestClientException firstFailure) {
            log.warn("TMDB call failed for {}, retrying once: {}", description, firstFailure.getMessage());
            try {
                return Optional.ofNullable(call.get());
            } catch (RestClientException secondFailure) {
                log.warn("TMDB call failed for {} after retry, skipping this cycle: {}", description, secondFailure.getMessage());
                return Optional.empty();
            }
        }
    }
}
