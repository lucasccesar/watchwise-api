package com.watchwise.watchwise_api.common.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbPosterPathValidatorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("Should Accept A TMDB Poster File Path - When It Has A File Name")
    void shouldAcceptTmdbPosterPathWhenItHasAFileName() {
        assertThat(TmdbPosterPathPolicy.isValid(TmdbPosterPathPolicy.PATH_PREFIX + "poster.png")).isTrue();
    }

    @Test
    @DisplayName("Should Reject An Absolute Poster URL")
    void shouldRejectAbsolutePosterUrl() {
        assertThat(TmdbPosterPathPolicy.isValid("https://image.tmdb.org/t/p/w342/poster.png")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Protocol-Relative Poster Path")
    void shouldRejectProtocolRelativePosterPath() {
        assertThat(TmdbPosterPathPolicy.isValid("//example.com/poster.png")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster Path - When It Has A Query")
    void shouldRejectPosterPathWhenItHasAQuery() {
        assertThat(TmdbPosterPathPolicy.isValid("/poster.png?size=w500")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster Path - When It Has A Fragment")
    void shouldRejectPosterPathWhenItHasAFragment() {
        assertThat(TmdbPosterPathPolicy.isValid("/poster.png#original")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster Path - When It Has No File Name")
    void shouldRejectPosterPathWhenItHasNoFileName() {
        assertThat(TmdbPosterPathPolicy.isValid(TmdbPosterPathPolicy.PATH_PREFIX)).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster Path - When It Has Leading Whitespace")
    void shouldRejectPosterPathWhenItHasLeadingWhitespace() {
        assertThat(TmdbPosterPathPolicy.isValid(" " + TmdbPosterPathPolicy.PATH_PREFIX + "poster.png")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster Path - When It Contains A Backslash")
    void shouldRejectPosterPathWhenItContainsABackslash() {
        assertThat(TmdbPosterPathPolicy.isValid("/poster\\image.png")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster Path - When It Contains A Dot Directory Segment")
    void shouldRejectPosterPathWhenItContainsADotDirectorySegment() {
        assertThat(TmdbPosterPathPolicy.isValid("/../poster.png")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster Path - When It Contains Whitespace")
    void shouldRejectPosterPathWhenItContainsWhitespace() {
        assertThat(TmdbPosterPathPolicy.isValid("/poster image.png")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Null Poster Path In The Service Policy")
    void shouldRejectNullPosterPathInTheServicePolicy() {
        assertThat(TmdbPosterPathPolicy.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("Should Reject An Empty Poster Path")
    void shouldRejectEmptyPosterPath() {
        assertThat(TmdbPosterPathPolicy.isValid("")).isFalse();
    }

    @Test
    @DisplayName("Should Accept A Null Poster Path In The Optional DTO Annotation")
    void shouldAcceptNullPosterPathInTheOptionalDtoAnnotation() {
        assertThat(validator.validate(new PosterRequest(null))).isEmpty();
    }

    private record PosterRequest(@TmdbPosterPath String customPosterUrl) {
    }
}
