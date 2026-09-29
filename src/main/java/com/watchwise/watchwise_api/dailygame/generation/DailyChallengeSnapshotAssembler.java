package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbCreator;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCompany;
import com.watchwise.watchwise_api.common.tmdb.TmdbProvider;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionProviders;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbWatchProviders;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DailyChallengeSnapshotAssembler {

    public DailyChallengeCandidate moviePoster(TmdbMovieSearchResult movie, String imagePath) {
        return candidate(DailyGameType.MOVIE_BY_POSTER, DailyGameTargetKind.MOVIE, movie.id(), null, null, null,
                null, "MOVIE:" + movie.id(), imagePath,
                answerSnapshot(DailyGameTargetKind.MOVIE, movie.id(), null, null, null, null, movie.title(), imagePath, null),
                displaySnapshot(imagePath), List.of());
    }

    public DailyChallengeCandidate seriesPoster(TmdbTvSearchResult series, String imagePath) {
        return candidate(DailyGameType.SERIES_BY_POSTER, DailyGameTargetKind.SERIES, series.id(), null, null, null,
                null, "SERIES:" + series.id(), imagePath,
                answerSnapshot(DailyGameTargetKind.SERIES, series.id(), null, null, null, null, series.name(), imagePath, null),
                displaySnapshot(imagePath), List.of());
    }

    public DailyChallengeCandidate person(DailyGameType gameType, String personTmdbId, String name, String imagePath,
                                          String sourceTmdbId, List<DailyChallengeCandidate.HintSnapshot> hints) {
        return candidate(gameType, DailyGameTargetKind.PERSON, personTmdbId, null, null, null, sourceTmdbId,
                "PERSON:" + personTmdbId, imagePath,
                answerSnapshot(DailyGameTargetKind.PERSON, null, personTmdbId, null, null, null, name, imagePath,
                        sourceTmdbId),
                displaySnapshot(imagePath), hints);
    }

    public DailyChallengeCandidate episode(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber,
                                           String name, List<String> imagePaths) {
        return episode(seriesTmdbId, seasonNumber, episodeNumber, name, null, null, null, imagePaths);
    }

    public DailyChallengeCandidate episode(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber,
                                           String name, String seriesName, String seriesPosterPath, Integer seriesYear,
                                           List<String> imagePaths) {
        String imagePath = imagePaths.getFirst();
        Map<String, Object> answerSnapshot = answerSnapshot(DailyGameTargetKind.EPISODE, null, null,
                seriesTmdbId, seasonNumber, episodeNumber, name, imagePath, seriesTmdbId);
        put(answerSnapshot, "seriesName", seriesName);
        put(answerSnapshot, "seriesPosterPath", seriesPosterPath);
        put(answerSnapshot, "seriesYear", seriesYear);
        put(answerSnapshot, "episodeName", name);
        return candidate(DailyGameType.EPISODE_BY_FRAME, DailyGameTargetKind.EPISODE, null, seriesTmdbId,
                seasonNumber, episodeNumber, seriesTmdbId,
                "EPISODE:" + seriesTmdbId + ":" + seasonNumber + ":" + episodeNumber, imagePath,
                answerSnapshot,
                displaySnapshot(imagePaths), List.of());
    }

    public DailyChallengeCandidate movieInfo(TmdbMovieFullDetails movie, String imagePath,
                                             List<DailyChallengeCandidate.HintSnapshot> hints) {
        return movieInfo(movie, imagePath, hints, null);
    }

    public DailyChallengeCandidate movieInfo(TmdbMovieFullDetails movie, String imagePath,
                                             List<DailyChallengeCandidate.HintSnapshot> hints,
                                             String certification) {
        Map<String, Object> answerSnapshot = answerSnapshot(DailyGameTargetKind.MOVIE, movie.id(), null,
                null, null, null, movie.title(), imagePath, movie.id());
        putNames(answerSnapshot, "platforms", providerNames(movie.watchProviders()));
        putNames(answerSnapshot, "genres", movie.genres() == null ? null
                : movie.genres().stream().map(value -> value == null ? null : value.name()).toList());
        put(answerSnapshot, "year", year(movie.releaseDate()));
        put(answerSnapshot, "certification", certification);
        put(answerSnapshot, "director", director(movie));
        putNames(answerSnapshot, "cast", movie.credits() == null || movie.credits().cast() == null ? null
                : movie.credits().cast().stream().map(value -> value == null ? null : value.name()).toList());
        putNames(answerSnapshot, "productionCompanies", movie.productionCompanies() == null ? null
                : movie.productionCompanies().stream().map(value -> value == null ? null : value.name()).toList());
        put(answerSnapshot, "revenue", movie.revenue() == null || movie.revenue() <= 0 ? null : movie.revenue());
        return candidate(DailyGameType.MOVIE_BY_INFO, DailyGameTargetKind.MOVIE, movie.id(), null, null, null,
                movie.id(), "MOVIE:" + movie.id(), imagePath,
                answerSnapshot,
                displaySnapshot(imagePath), hints);
    }

    public DailyChallengeCandidate seriesInfo(TmdbTvFullDetails series, String imagePath,
                                              List<DailyChallengeCandidate.HintSnapshot> hints) {
        return seriesInfo(series, imagePath, hints, null);
    }

    public DailyChallengeCandidate seriesInfo(TmdbTvFullDetails series, String imagePath,
                                              List<DailyChallengeCandidate.HintSnapshot> hints,
                                              String certification) {
        Map<String, Object> answerSnapshot = answerSnapshot(DailyGameTargetKind.SERIES, series.id(), null,
                null, null, null, series.name(), imagePath, series.id());
        putNames(answerSnapshot, "platforms", providerNames(series.watchProviders()));
        putNames(answerSnapshot, "genres", series.genres() == null ? null
                : series.genres().stream().map(value -> value == null ? null : value.name()).toList());
        put(answerSnapshot, "year", year(series.firstAirDate()));
        put(answerSnapshot, "certification", certification);
        putNames(answerSnapshot, "creators", series.createdBy() == null ? null
                : series.createdBy().stream().map(value -> value == null ? null : value.name()).toList());
        putNames(answerSnapshot, "cast", series.aggregateCredits() == null || series.aggregateCredits().cast() == null
                ? null : series.aggregateCredits().cast().stream()
                .map(value -> value == null ? null : value.name()).toList());
        putNames(answerSnapshot, "productionCompanies", series.productionCompanies() == null ? null
                : series.productionCompanies().stream().map(value -> value == null ? null : value.name()).toList());
        put(answerSnapshot, "seasons", series.numberOfSeasons() == null || series.numberOfSeasons() <= 0
                ? null : series.numberOfSeasons());
        return candidate(DailyGameType.SERIES_BY_INFO, DailyGameTargetKind.SERIES, series.id(), null, null, null,
                series.id(), "SERIES:" + series.id(), imagePath,
                answerSnapshot,
                displaySnapshot(imagePath), hints);
    }

    public DailyChallengeCandidate actorFromMovie(String movieTmdbId, TmdbCastMember actor, String imagePath) {
        return person(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, String.valueOf(actor.id()), actor.name(), imagePath,
                movieTmdbId, List.of());
    }

    public DailyChallengeCandidate actorFromSeries(String seriesTmdbId, TmdbAggregateCastMember actor, String imagePath) {
        return person(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY, String.valueOf(actor.id()), actor.name(), imagePath,
                seriesTmdbId, List.of());
    }

    public Map<String, Object> answerSnapshot(DailyGameTargetKind targetKind, String targetTmdbId, String personTmdbId,
                                   String seriesTmdbId, Integer seasonNumber, Integer episodeNumber, String title,
                                   String imagePath, String sourceTmdbId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("targetKind", targetKind.name());
        put(snapshot, "tmdbId", targetTmdbId);
        put(snapshot, "personTmdbId", personTmdbId);
        put(snapshot, "seriesTmdbId", seriesTmdbId);
        if (seasonNumber != null) {
            snapshot.put("seasonNumber", seasonNumber);
        }
        if (episodeNumber != null) {
            snapshot.put("episodeNumber", episodeNumber);
        }
        put(snapshot, "title", title);
        put(snapshot, "imageUrl", imagePath);
        put(snapshot, "sourceTmdbId", sourceTmdbId);
        return snapshot;
    }

    public Map<String, Object> displaySnapshot(String imagePath) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("imageUrl", imagePath);
        return snapshot;
    }

    public Map<String, Object> displaySnapshot(List<String> imagePaths) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("imagePaths", List.copyOf(imagePaths));
        return snapshot;
    }

    public static DailyChallengeCandidate.HintSnapshot hint(String type, String value) {
        return new DailyChallengeCandidate.HintSnapshot(type, value);
    }

    private DailyChallengeCandidate candidate(DailyGameType gameType, DailyGameTargetKind targetKind,
                                              String targetTmdbId, String seriesTmdbId, Integer seasonNumber,
                                              Integer episodeNumber, String sourceTmdbId, String answerKey,
                                              String imagePath, Map<String, Object> answerSnapshot,
                                              Map<String, Object> displaySnapshot,
                                              List<DailyChallengeCandidate.HintSnapshot> hints) {
        return new DailyChallengeCandidate(gameType, targetKind, targetTmdbId, seriesTmdbId, seasonNumber,
                episodeNumber, sourceTmdbId, answerKey, imagePath, answerSnapshot, displaySnapshot, hints);
    }

    private static void put(Map<String, Object> snapshot, String field, String value) {
        if (value != null && !value.isBlank()) {
            snapshot.put(field, value);
        }
    }

    private static void put(Map<String, Object> snapshot, String field, Object value) {
        if (value != null) {
            snapshot.put(field, value);
        }
    }

    private static void putNames(Map<String, Object> snapshot, String field, List<String> values) {
        if (values == null) {
            return;
        }
        List<String> normalized = values.stream()
                .map(DailyChallengeSnapshotAssembler::trimmed)
                .filter(value -> value != null)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(value -> value.toLowerCase(java.util.Locale.ROOT),
                                value -> value, (first, ignored) -> first, LinkedHashMap::new),
                        map -> List.copyOf(map.values())));
        if (!normalized.isEmpty()) {
            snapshot.put(field, normalized);
        }
    }

    private List<String> providerNames(TmdbWatchProviders providers) {
        if (providers == null || providers.results() == null) {
            return List.of();
        }
        TmdbRegionProviders region = providers.results().get("BR");
        if (region == null) {
            return List.of();
        }
        List<String> names = new java.util.ArrayList<>();
        addProviderNames(names, region.flatrate());
        addProviderNames(names, region.rent());
        addProviderNames(names, region.buy());
        return names;
    }

    private void addProviderNames(List<String> names, List<TmdbProvider> providers) {
        if (providers != null) {
            providers.stream().filter(value -> value != null).map(TmdbProvider::providerName).forEach(names::add);
        }
    }

    private String director(TmdbMovieFullDetails movie) {
        if (movie.credits() == null || movie.credits().crew() == null) {
            return null;
        }
        return movie.credits().crew().stream()
                .filter(value -> value != null && value.job() != null && "Director".equalsIgnoreCase(value.job()))
                .map(value -> trimmed(value.name()))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }

    private Integer year(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(date).getYear();
        } catch (java.time.format.DateTimeParseException ignored) {
            try {
                return Integer.valueOf(date.substring(0, 4));
            } catch (RuntimeException ignoredAgain) {
                return null;
            }
        }
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
