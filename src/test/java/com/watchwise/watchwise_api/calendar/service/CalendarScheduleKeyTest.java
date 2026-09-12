package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarScheduleKeyTest {

    @Test
    void shouldAcceptMovieAndSeriesScheduleKeys() {
        assertThatCode(() -> new CalendarScheduleKey(ContentType.MOVIE, "550", "en-US", "US"))
                .doesNotThrowAnyException();
        assertThatCode(() -> new CalendarScheduleKey(ContentType.SERIES, "1396", "pt-BR", "BR"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("unsupportedTypes")
    void shouldRejectUnsupportedContentTypes(ContentType type) {
        assertThatThrownBy(() -> new CalendarScheduleKey(type, "550", "en-US", "US"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidTmdbIds")
    void shouldRejectMissingTmdbId(String tmdbId) {
        assertThatThrownBy(() -> new CalendarScheduleKey(ContentType.MOVIE, tmdbId, "en-US", "US"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidLocales")
    void shouldRejectInvalidLocales(String preferredLanguage, String preferredRegion) {
        assertThatThrownBy(() -> new CalendarScheduleKey(
                ContentType.MOVIE, "550", preferredLanguage, preferredRegion))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> unsupportedTypes() {
        return Stream.of(
                Arguments.of((ContentType) null),
                Arguments.of(ContentType.SEASON),
                Arguments.of(ContentType.EPISODE));
    }

    private static Stream<Arguments> invalidTmdbIds() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of(" "));
    }

    private static Stream<Arguments> invalidLocales() {
        return Stream.of(
                Arguments.of(null, "US"),
                Arguments.of("", "US"),
                Arguments.of("en-us", "US"),
                Arguments.of("zz-ZZ", "US"),
                Arguments.of("en-US", null),
                Arguments.of("en-US", ""),
                Arguments.of("en-US", "us"),
                Arguments.of("en-US", "ZZ"));
    }
}
