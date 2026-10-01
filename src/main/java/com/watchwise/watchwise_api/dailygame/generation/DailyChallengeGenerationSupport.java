package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.Locale;
import java.util.Set;

final class DailyChallengeGenerationSupport {

    private static final Set<String> ASIAN_ORIGINAL_LANGUAGES = Set.of(
            "ar", "az", "bn", "bo", "fa", "fil", "gu", "he", "hi", "hy", "id", "ja", "jv",
            "ka", "kk", "km", "ko", "ku", "ky", "lo", "ml", "mn", "mr", "ms", "my", "ne", "pa",
            "ps", "si", "su", "ta", "te", "th", "tl", "tr", "ug", "ur", "uz", "vi", "yue", "zh");

    private DailyChallengeGenerationSupport() {
    }

    static int randomPage() {
        return ThreadLocalRandom.current().nextInt(1, 21);
    }

    static boolean isAsianOriginalLanguage(String originalLanguage) {
        if (originalLanguage == null || originalLanguage.isBlank()) {
            return false;
        }
        String normalized = originalLanguage.trim().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        return ASIAN_ORIGINAL_LANGUAGES.contains(normalized);
    }

    static <T> Optional<T> randomItem(TmdbLookupResult<TmdbSearchPage<T>> lookup) {
        return value(lookup).flatMap(page -> randomItem(page.results()));
    }

    static <T> Optional<T> randomItem(
            TmdbLookupResult<TmdbSearchPage<T>> lookup, Predicate<T> predicate) {
        return value(lookup).flatMap(page -> randomItem(page.results(), predicate));
    }

    static <T> Optional<T> randomItem(List<T> items) {
        return randomItem(items, item -> true);
    }

    static <T> Optional<T> randomItem(List<T> items, Predicate<T> predicate) {
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        List<T> eligibleItems = items.stream().filter(predicate).toList();
        if (eligibleItems.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(eligibleItems.get(ThreadLocalRandom.current().nextInt(eligibleItems.size())));
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
