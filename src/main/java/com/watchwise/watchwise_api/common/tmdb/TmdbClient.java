package com.watchwise.watchwise_api.common.tmdb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
