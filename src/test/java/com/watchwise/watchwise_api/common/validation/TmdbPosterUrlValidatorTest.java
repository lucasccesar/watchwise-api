package com.watchwise.watchwise_api.common.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbPosterUrlValidatorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("Should Accept A TMDB W342 Poster URL - When The Prefix Has A Suffix")
    void shouldAcceptTmdbW342PosterUrlWhenThePrefixHasASuffix() {
        assertThat(TmdbPosterUrlPolicy.isValid(TmdbPosterUrlPolicy.PREFIX + "poster.png")).isTrue();
    }

    @Test
    @DisplayName("Should Reject A Poster URL - When The Scheme Is HTTP")
    void shouldRejectPosterUrlWhenTheSchemeIsHttp() {
        assertThat(TmdbPosterUrlPolicy.isValid("http://image.tmdb.org/t/p/w342/poster.png")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster URL - When The Host Is Not TMDB")
    void shouldRejectPosterUrlWhenTheHostIsNotTmdb() {
        assertThat(TmdbPosterUrlPolicy.isValid("https://example.com/t/p/w342/poster.png")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster URL - When The TMDB Size Is W500")
    void shouldRejectPosterUrlWhenTheTmdbSizeIsW500() {
        assertThat(TmdbPosterUrlPolicy.isValid("https://image.tmdb.org/t/p/w500/poster.png")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster URL - When The TMDB Size Is Original")
    void shouldRejectPosterUrlWhenTheTmdbSizeIsOriginal() {
        assertThat(TmdbPosterUrlPolicy.isValid("https://image.tmdb.org/t/p/original/poster.png")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster URL - When The Prefix Has No Suffix")
    void shouldRejectPosterUrlWhenThePrefixHasNoSuffix() {
        assertThat(TmdbPosterUrlPolicy.isValid(TmdbPosterUrlPolicy.PREFIX)).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster URL - When It Has Leading Whitespace")
    void shouldRejectPosterUrlWhenItHasLeadingWhitespace() {
        assertThat(TmdbPosterUrlPolicy.isValid(" " + TmdbPosterUrlPolicy.PREFIX + "poster.png")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster URL - When Its Suffix Is Only Whitespace")
    void shouldRejectPosterUrlWhenItsSuffixIsOnlyWhitespace() {
        assertThat(TmdbPosterUrlPolicy.isValid(TmdbPosterUrlPolicy.PREFIX + " ")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster URL - When Its Suffix Has Trailing Whitespace")
    void shouldRejectPosterUrlWhenItsSuffixHasTrailingWhitespace() {
        assertThat(TmdbPosterUrlPolicy.isValid(TmdbPosterUrlPolicy.PREFIX + "poster.png ")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Poster URL - When Its Path Contains Whitespace")
    void shouldRejectPosterUrlWhenItsPathContainsWhitespace() {
        assertThat(TmdbPosterUrlPolicy.isValid(TmdbPosterUrlPolicy.PREFIX + "poster image.png")).isFalse();
    }

    @Test
    @DisplayName("Should Reject A Null Poster URL In The Service Policy")
    void shouldRejectNullPosterUrlInTheServicePolicy() {
        assertThat(TmdbPosterUrlPolicy.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("Should Reject An Empty Poster URL - When It Has No Prefix")
    void shouldRejectEmptyPosterUrlWhenItHasNoPrefix() {
        assertThat(TmdbPosterUrlPolicy.isValid("")).isFalse();
    }

    @Test
    @DisplayName("Should Accept A Null Poster URL In The Optional DTO Annotation")
    void shouldAcceptNullPosterUrlInTheOptionalDtoAnnotation() {
        assertThat(validator.validate(new PosterRequest(null))).isEmpty();
    }

    private record PosterRequest(@TmdbPosterUrl String customPosterUrl) {
    }
}
