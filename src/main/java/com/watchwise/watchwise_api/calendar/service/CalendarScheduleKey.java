package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.util.Locale;
import java.util.Set;

public record CalendarScheduleKey(
        ContentType type,
        String tmdbId,
        String preferredLanguage,
        String preferredRegion) {

    private static final Set<String> ISO_LANGUAGES = Set.of(Locale.getISOLanguages());
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    public CalendarScheduleKey {
        if (type != ContentType.MOVIE && type != ContentType.SERIES) {
            throw new IllegalArgumentException("Calendar schedule keys require MOVIE or SERIES content");
        }
        if (isBlank(tmdbId)) {
            throw new IllegalArgumentException("Calendar schedule keys require a TMDB ID");
        }
        if (!isValidLanguage(preferredLanguage)) {
            throw new IllegalArgumentException("Calendar schedule keys require a valid language-region value");
        }
        if (!isValidCountry(preferredRegion)) {
            throw new IllegalArgumentException("Calendar schedule keys require a valid region value");
        }
    }

    private static boolean isValidLanguage(String languageTag) {
        return languageTag != null
                && languageTag.matches("[a-z]{2}-[A-Z]{2}")
                && ISO_LANGUAGES.contains(languageTag.substring(0, 2))
                && ISO_COUNTRIES.contains(languageTag.substring(3));
    }

    private static boolean isValidCountry(String country) {
        return country != null && country.matches("[A-Z]{2}") && ISO_COUNTRIES.contains(country);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
