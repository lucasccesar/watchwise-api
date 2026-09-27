package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

final class DailyChallengeGenerationSupport {

    private DailyChallengeGenerationSupport() {
    }

    static int randomPage() {
        return ThreadLocalRandom.current().nextInt(1, 51);
    }

    static <T> Optional<T> randomItem(TmdbLookupResult<TmdbSearchPage<T>> lookup) {
        return value(lookup).flatMap(page -> randomItem(page.results()));
    }

    static <T> Optional<T> randomItem(List<T> items) {
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(items.get(ThreadLocalRandom.current().nextInt(items.size())));
    }

    static <T> Optional<T> value(TmdbLookupResult<T> lookup) {
        if (lookup instanceof TmdbLookupResult.Found<T> found) {
            return Optional.ofNullable(found.value());
        }
        return Optional.empty();
    }

    static boolean validId(String value) {
        return value != null && value.matches("[1-9]\\d*");
    }

    static boolean validImage(String value) {
        return value != null && !value.isBlank();
    }

    static Optional<LocalDate> date(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    static String joinNonBlank(List<String> values) {
        if (values == null) {
            return null;
        }
        String joined = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse(null);
        return joined == null || joined.isBlank() ? null : joined;
    }
}
