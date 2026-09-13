package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class CalendarMovieReleaseDateSelector {

    private static final List<Integer> RELEASE_TYPE_PRECEDENCE = List.of(3, 2, 1, 4, 5, 6);

    private CalendarMovieReleaseDateSelector() {
    }

    public static Optional<LocalDate> select(TmdbMovieReleaseDates releases, String region) {
        if (releases == null || releases.results() == null) {
            return Optional.empty();
        }
        return releases.results().stream()
                .filter(candidate -> candidate != null && region.equals(candidate.isoCode()))
                .findFirst()
                .flatMap(CalendarMovieReleaseDateSelector::selectFromRegion);
    }

    private static Optional<LocalDate> selectFromRegion(TmdbRegionReleaseDates region) {
        if (region.releaseDates() == null) {
            return Optional.empty();
        }
        return region.releaseDates().stream()
                .filter(Objects::nonNull)
                .map(release -> new RankedReleaseDate(release, parseDate(release.releaseDate())))
                .filter(candidate -> candidate.date() != null)
                .sorted(Comparator.comparingInt((RankedReleaseDate candidate) -> releaseTypeRank(candidate.release().type()))
                        .thenComparing(RankedReleaseDate::date))
                .map(RankedReleaseDate::date)
                .findFirst();
    }

    private static int releaseTypeRank(Integer type) {
        int rank = RELEASE_TYPE_PRECEDENCE.indexOf(type);
        return rank >= 0 ? rank : RELEASE_TYPE_PRECEDENCE.size();
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException | IndexOutOfBoundsException ignored) {
            return null;
        }
    }

    private record RankedReleaseDate(TmdbMovieReleaseDate release, LocalDate date) {
    }
}
