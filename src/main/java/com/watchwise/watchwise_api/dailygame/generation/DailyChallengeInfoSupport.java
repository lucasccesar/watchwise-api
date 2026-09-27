package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbProvider;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionProviders;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvContentRatings;
import com.watchwise.watchwise_api.common.tmdb.TmdbWatchProviders;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class DailyChallengeInfoSupport {

    private DailyChallengeInfoSupport() {
    }

    static String providers(TmdbWatchProviders providers, String region) {
        if (providers == null || providers.results() == null) {
            return null;
        }
        TmdbRegionProviders regional = providers.results().get(region);
        if (regional == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        addProviderNames(names, regional.flatrate());
        addProviderNames(names, regional.rent());
        addProviderNames(names, regional.buy());
        return DailyChallengeGenerationSupport.joinNonBlank(names);
    }

    static Optional<String> movieCertification(TmdbMovieReleaseDates releaseDates, String region) {
        if (releaseDates == null || releaseDates.results() == null) {
            return Optional.empty();
        }
        return releaseDates.results().stream()
                .filter(result -> result != null && region.equals(result.isoCode()))
                .flatMap(result -> result.releaseDates() == null ? java.util.stream.Stream.empty() : result.releaseDates().stream())
                .map(com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate::certification)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    static Optional<String> tvCertification(TmdbTvContentRatings ratings, String region) {
        if (ratings == null || ratings.results() == null) {
            return Optional.empty();
        }
        return ratings.results().stream()
                .filter(result -> result != null && region.equals(result.isoCode()))
                .map(com.watchwise.watchwise_api.common.tmdb.TmdbTvContentRating::rating)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private static void addProviderNames(List<String> names, List<TmdbProvider> providers) {
        if (providers != null) {
            providers.stream().map(TmdbProvider::providerName).forEach(names::add);
        }
    }
}
