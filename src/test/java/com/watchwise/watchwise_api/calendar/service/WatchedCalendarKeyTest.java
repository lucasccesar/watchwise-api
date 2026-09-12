package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WatchedCalendarKeyTest {

    @Test
    void shouldCreateValidMovieAndEpisodeKeys() {
        assertThat(WatchedCalendarKey.movie("550"))
                .isEqualTo(new WatchedCalendarKey(ContentType.MOVIE, "550", null, null, null));
        assertThat(WatchedCalendarKey.episode("1396", 1, 2))
                .isEqualTo(new WatchedCalendarKey(ContentType.EPISODE, null, "1396", 1, 2));
    }

    @ParameterizedTest
    @MethodSource("unsupportedTypes")
    void shouldRejectUnsupportedContentTypes(ContentType type) {
        assertThatThrownBy(() -> new WatchedCalendarKey(type, "550", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidMovieShapes")
    void shouldRejectInvalidMovieShapes(
            String tmdbId, String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        assertThatThrownBy(() -> new WatchedCalendarKey(
                ContentType.MOVIE, tmdbId, seriesTmdbId, seasonNumber, episodeNumber))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidEpisodeShapes")
    void shouldRejectInvalidEpisodeShapes(
            String tmdbId, String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        assertThatThrownBy(() -> new WatchedCalendarKey(
                ContentType.EPISODE, tmdbId, seriesTmdbId, seasonNumber, episodeNumber))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> unsupportedTypes() {
        return Stream.of(
                Arguments.of((ContentType) null),
                Arguments.of(ContentType.SERIES),
                Arguments.of(ContentType.SEASON));
    }

    private static Stream<Arguments> invalidMovieShapes() {
        return Stream.of(
                Arguments.of(null, null, null, null),
                Arguments.of("", null, null, null),
                Arguments.of(" ", null, null, null),
                Arguments.of("550", "1396", null, null),
                Arguments.of("550", null, 1, null),
                Arguments.of("550", null, null, 1));
    }

    private static Stream<Arguments> invalidEpisodeShapes() {
        return Stream.of(
                Arguments.of(null, null, 1, 1),
                Arguments.of(null, "", 1, 1),
                Arguments.of(null, " ", 1, 1),
                Arguments.of("550", "1396", 1, 1),
                Arguments.of(null, "1396", null, 1),
                Arguments.of(null, "1396", 1, null),
                Arguments.of(null, "1396", 0, 1),
                Arguments.of(null, "1396", -1, 1),
                Arguments.of(null, "1396", 1, 0),
                Arguments.of(null, "1396", 1, -1));
    }
}
