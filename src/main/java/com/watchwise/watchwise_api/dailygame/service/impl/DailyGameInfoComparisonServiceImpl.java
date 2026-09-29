package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbCrewMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCompany;
import com.watchwise.watchwise_api.common.tmdb.TmdbProvider;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionProviders;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvContentRating;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvContentRatings;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbWatchProviders;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameComparisonCellDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameComparisonDirection;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameComparisonStatus;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameInfoFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.service.DailyGameCandidateIdentity;
import com.watchwise.watchwise_api.dailygame.service.DailyGameInfoComparisonService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DailyGameInfoComparisonServiceImpl implements DailyGameInfoComparisonService {

    private static final String LANGUAGE = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;
    private static final String RATING_REGION = "BR";
    private static final BigDecimal TEN_PERCENT = new BigDecimal("0.10");
    private static final BigDecimal THIRTY_PERCENT = new BigDecimal("0.30");

    private final TmdbClient tmdbClient;

    public DailyGameInfoComparisonServiceImpl(TmdbClient tmdbClient) {
        this.tmdbClient = tmdbClient;
    }

    @Override
    public DailyGameInfoFeedbackDTO compare(DailyChallenge challenge, DailyGameCandidateIdentity candidate) {
        if (challenge == null || candidate == null || challenge.getAnswerSnapshot() == null) {
            throw new BadRequestException("A daily game info comparison is required");
        }
        return switch (challenge.getGameType()) {
            case MOVIE_BY_INFO -> compareMovie(challenge.getAnswerSnapshot(), candidate);
            case SERIES_BY_INFO -> compareSeries(challenge.getAnswerSnapshot(), candidate);
            default -> throw new BadRequestException("Info comparison is not supported for this daily game");
        };
    }

    private DailyGameInfoFeedbackDTO compareMovie(Map<String, Object> secret,
                                                   DailyGameCandidateIdentity candidate) {
        requireTarget(candidate, "movie", com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind.MOVIE);
        TmdbMovieFullDetails movie = found(tmdbClient.getMovieFullDetails(candidate.tmdbId(), LANGUAGE), "movie");
        String certification = movieCertification(optional(tmdbClient.getMovieReleaseDates(
                candidate.tmdbId(), LANGUAGE)));
        List<String> platforms = providers(movie.watchProviders());
        List<String> genres = names(movie.genres(), genre -> genre == null ? null : genre.name());
        List<String> cast = names(movie.credits() == null ? null : movie.credits().cast(),
                value -> value == null ? null : value.name());
        List<String> productionCompanies = names(movie.productionCompanies(),
                value -> value == null ? null : value.name());
        String director = movie.credits() == null ? null : movie.credits().crew() == null ? null
                : movie.credits().crew().stream()
                .filter(value -> value != null && value.job() != null && "Director".equalsIgnoreCase(value.job()))
                .map(TmdbCrewMember::name)
                .map(this::trimmed)
                .filter(value -> value != null)
                .findFirst().orElse(null);
        Integer year = year(movie.releaseDate());
        Long revenue = positive(movie.revenue());
        return feedback(
                setCell(secret.get("platforms"), platforms),
                setCell(secret.get("genres"), genres),
                yearCell(secret.get("year"), year),
                scalarCell(secret.get("certification"), certification),
                scalarCell(secret.get("director"), director),
                castCell(secret.get("cast"), cast),
                setCell(secret.get("productionCompanies"), productionCompanies),
                numericCell(secret.get("revenue"), revenue, false));
    }

    private DailyGameInfoFeedbackDTO compareSeries(Map<String, Object> secret,
                                                    DailyGameCandidateIdentity candidate) {
        requireTarget(candidate, "series", com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind.SERIES);
        TmdbTvFullDetails series = found(tmdbClient.getTvFullDetails(candidate.tmdbId(), LANGUAGE), "series");
        String certification = tvCertification(optional(tmdbClient.getTvContentRatings(
                candidate.tmdbId(), LANGUAGE)));
        List<String> platforms = providers(series.watchProviders());
        List<String> genres = names(series.genres(), genre -> genre == null ? null : genre.name());
        List<String> cast = names(series.aggregateCredits() == null ? null : series.aggregateCredits().cast(),
                value -> value == null ? null : value.name());
        List<String> productionCompanies = names(series.productionCompanies(),
                value -> value == null ? null : value.name());
        List<String> creators = names(series.createdBy(), value -> value == null ? null : value.name());
        Integer year = year(series.firstAirDate());
        Integer seasons = positive(series.numberOfSeasons());
        Object creatorsSnapshot = secret.containsKey("creators")
                ? secret.get("creators") : secret.get("directorOrCreators");
        return feedback(
                setCell(secret.get("platforms"), platforms),
                setCell(secret.get("genres"), genres),
                yearCell(secret.get("year"), year),
                scalarCell(secret.get("certification"), certification),
                setCell(creatorsSnapshot, creators),
                castCell(secret.get("cast"), cast),
                setCell(secret.get("productionCompanies"), productionCompanies),
                numericCell(secret.get("seasons"), seasons, true));
    }

    private DailyGameInfoFeedbackDTO feedback(DailyGameComparisonCellDTO platforms,
                                               DailyGameComparisonCellDTO genres,
                                               DailyGameComparisonCellDTO year,
                                               DailyGameComparisonCellDTO certification,
                                               DailyGameComparisonCellDTO directorOrCreators,
                                               DailyGameComparisonCellDTO cast,
                                               DailyGameComparisonCellDTO productionCompanies,
                                               DailyGameComparisonCellDTO revenueOrSeasons) {
        return new DailyGameInfoFeedbackDTO(platforms, genres, year, certification, directorOrCreators,
                cast, productionCompanies, revenueOrSeasons);
    }

    private DailyGameComparisonCellDTO setCell(Object secretValue, List<String> candidateValues) {
        List<String> secretValues = values(secretValue);
        if (secretValues.isEmpty() || candidateValues.isEmpty()) {
            return noData();
        }
        Set<String> secretKeys = secretValues.stream().map(this::key).collect(Collectors.toCollection(HashSet::new));
        List<String> matched = candidateValues.stream().filter(value -> secretKeys.contains(key(value))).toList();
        DailyGameComparisonStatus status = sameSet(secretValues, candidateValues)
                ? DailyGameComparisonStatus.MATCH
                : matched.isEmpty() ? DailyGameComparisonStatus.NO_MATCH : DailyGameComparisonStatus.PARTIAL;
        return cell(status, null, candidateValues, matched, matched.size());
    }

    private DailyGameComparisonCellDTO castCell(Object secretValue, List<String> candidateValues) {
        List<String> secretValues = values(secretValue);
        if (secretValues.isEmpty() || candidateValues.isEmpty()) {
            return noData();
        }
        Set<String> secretKeys = secretValues.stream().map(this::key).collect(Collectors.toSet());
        List<String> matched = candidateValues.stream().filter(value -> secretKeys.contains(key(value))).toList();
        DailyGameComparisonStatus status = matched.isEmpty() ? DailyGameComparisonStatus.NO_MATCH
                : matched.size() >= 3 ? DailyGameComparisonStatus.MATCH : DailyGameComparisonStatus.PARTIAL;
        return cell(status, null, candidateValues, matched, matched.size());
    }

    private DailyGameComparisonCellDTO scalarCell(Object secretValue, String candidateValue) {
        String secret = trimmed(secretValue == null ? null : String.valueOf(secretValue));
        if (secret == null || candidateValue == null) {
            return noData();
        }
        return cell(key(secret).equals(key(candidateValue)) ? DailyGameComparisonStatus.MATCH
                        : DailyGameComparisonStatus.NO_MATCH,
                null, candidateValue, List.of(), null);
    }

    private DailyGameComparisonCellDTO yearCell(Object secretValue, Integer candidateValue) {
        Integer secret = integer(secretValue);
        if (secret == null || candidateValue == null) {
            return noData();
        }
        int difference = Math.abs(secret - candidateValue);
        DailyGameComparisonStatus status = difference == 0 ? DailyGameComparisonStatus.MATCH
                : difference == 1 ? DailyGameComparisonStatus.PARTIAL : DailyGameComparisonStatus.NO_MATCH;
        return cell(status, direction(secret, candidateValue), candidateValue, List.of(), null);
    }

    private DailyGameComparisonCellDTO numericCell(Object secretValue, Number candidateValue, boolean exactOnly) {
        Long secret = longValue(secretValue);
        Long candidate = candidateValue == null ? null : candidateValue.longValue();
        if (secret == null || candidate == null || secret <= 0 || candidate <= 0) {
            return noData();
        }
        DailyGameComparisonStatus status;
        if (secret.equals(candidate)) {
            status = DailyGameComparisonStatus.MATCH;
        } else if (exactOnly) {
            status = DailyGameComparisonStatus.NO_MATCH;
        } else {
            BigDecimal difference = BigDecimal.valueOf(secret).subtract(BigDecimal.valueOf(candidate)).abs();
            BigDecimal larger = BigDecimal.valueOf(Math.max(secret, candidate));
            status = difference.compareTo(larger.multiply(TEN_PERCENT)) <= 0 ? DailyGameComparisonStatus.MATCH
                    : difference.compareTo(larger.multiply(THIRTY_PERCENT)) <= 0
                    ? DailyGameComparisonStatus.PARTIAL : DailyGameComparisonStatus.NO_MATCH;
        }
        return cell(status, direction(secret, candidate), candidateValue, List.of(), null);
    }

    private DailyGameComparisonDirection direction(Number secret, Number candidate) {
        int comparison = Long.compare(secret.longValue(), candidate.longValue());
        return comparison == 0 ? null
                : comparison > 0 ? DailyGameComparisonDirection.SECRET_HIGHER
                : DailyGameComparisonDirection.SECRET_LOWER;
    }

    private boolean sameSet(List<String> left, List<String> right) {
        return left.stream().map(this::key).collect(Collectors.toSet())
                .equals(right.stream().map(this::key).collect(Collectors.toSet()));
    }

    private DailyGameComparisonCellDTO noData() {
        return cell(DailyGameComparisonStatus.NO_DATA, null, null, List.of(), null);
    }

    private DailyGameComparisonCellDTO cell(DailyGameComparisonStatus status,
                                             DailyGameComparisonDirection direction,
                                             Object displayValue, List<String> matchedValues, Integer matchCount) {
        return new DailyGameComparisonCellDTO(status, direction, displayValue, matchedValues, matchCount);
    }

    private List<String> values(Object value) {
        if (value instanceof List<?> list) {
            return normalize(list.stream().map(String::valueOf).toList());
        }
        if (value instanceof String string) {
            return normalize(List.of(string.split(",")));
        }
        return List.of();
    }

    private <T> List<String> names(List<T> source, Function<T, String> mapper) {
        if (source == null) {
            return List.of();
        }
        return normalize(source.stream().map(mapper).toList());
    }

    private List<String> normalize(List<String> values) {
        Map<String, String> normalized = new LinkedHashMap<>();
        values.stream().map(this::trimmed).filter(value -> value != null)
                .forEach(value -> normalized.putIfAbsent(key(value), value));
        return List.copyOf(normalized.values());
    }

    private List<String> providers(TmdbWatchProviders watchProviders) {
        if (watchProviders == null || watchProviders.results() == null) {
            return List.of();
        }
        TmdbRegionProviders region = watchProviders.results().get(RATING_REGION);
        if (region == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        addProviders(names, region.flatrate());
        addProviders(names, region.rent());
        addProviders(names, region.buy());
        return normalize(names);
    }

    private void addProviders(List<String> names, List<TmdbProvider> providers) {
        if (providers != null) {
            providers.stream().filter(value -> value != null).map(TmdbProvider::providerName).forEach(names::add);
        }
    }

    private String movieCertification(TmdbMovieReleaseDates releaseDates) {
        if (releaseDates == null || releaseDates.results() == null) {
            return null;
        }
        return releaseDates.results().stream()
                .filter(value -> value != null && RATING_REGION.equals(value.isoCode()))
                .flatMap(value -> value.releaseDates() == null ? java.util.stream.Stream.empty()
                        : value.releaseDates().stream())
                .filter(value -> value != null)
                .map(TmdbMovieReleaseDate::certification)
                .map(this::trimmed)
                .filter(value -> value != null)
                .findFirst().orElse(null);
    }

    private String tvCertification(TmdbTvContentRatings ratings) {
        if (ratings == null || ratings.results() == null) {
            return null;
        }
        return ratings.results().stream()
                .filter(value -> value != null && RATING_REGION.equals(value.isoCode()))
                .map(TmdbTvContentRating::rating)
                .map(this::trimmed)
                .filter(value -> value != null)
                .findFirst().orElse(null);
    }

    private Integer year(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value).getYear();
        } catch (DateTimeParseException ignored) {
            try {
                return Integer.valueOf(value.substring(0, 4));
            } catch (RuntimeException ignoredAgain) {
                return null;
            }
        }
    }

    private Long positive(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    private Integer positive(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String key(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String trimmed(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).trim();
        return result.isBlank() ? null : result;
    }

    private void requireTarget(DailyGameCandidateIdentity candidate, String target,
                               com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind expectedKind) {
        if (candidate.targetKind() != expectedKind || candidate.tmdbId() == null || candidate.tmdbId().isBlank()) {
            throw new BadRequestException("A " + target + " candidate is required");
        }
    }

    private <T> T found(TmdbLookupResult<T> result, String target) {
        if (result == null || result.isUnavailable()) {
            throw new TmdbUnavailableException("TMDB is temporarily unavailable");
        }
        if (result.isNotFound()) {
            throw new BadRequestException("The selected " + target + " does not exist");
        }
        if (result instanceof TmdbLookupResult.Found<T> found && found.value() != null) {
            return found.value();
        }
        throw new TmdbUnavailableException("TMDB is temporarily unavailable");
    }

    private <T> T optional(TmdbLookupResult<T> result) {
        if (result == null || result instanceof TmdbLookupResult.NotFound<T>) {
            return null;
        }
        if (result.isUnavailable()) {
            throw new TmdbUnavailableException("TMDB is temporarily unavailable");
        }
        if (result instanceof TmdbLookupResult.Found<T> found) {
            return found.value();
        }
        return null;
    }
}
