package com.watchwise.watchwise_api.common.tmdb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbImageUrlBuilderTest {

    @Test
    @DisplayName("Should Build W500 Poster URL - When TMDB Returns A Relative Path")
    void shouldBuildW500PosterUrlWhenTmdbReturnsRelativePath() {
        assertThat(TmdbImageUrlBuilder.posterUrl("/alien.jpg"))
                .isEqualTo("https://image.tmdb.org/t/p/w500/alien.jpg");
    }

    @Test
    @DisplayName("Should Build W185 Profile URL - When TMDB Returns A Relative Path")
    void shouldBuildW185ProfileUrlWhenTmdbReturnsRelativePath() {
        assertThat(TmdbImageUrlBuilder.profileUrl("/sigourney.jpg"))
                .isEqualTo("https://image.tmdb.org/t/p/w185/sigourney.jpg");
    }

    @Test
    @DisplayName("Should Return Null Poster URL - When TMDB Path Is Missing")
    void shouldReturnNullPosterUrlWhenTmdbPathIsMissing() {
        assertThat(TmdbImageUrlBuilder.posterUrl(null)).isNull();
    }

    @Test
    @DisplayName("Should Return Null Profile URL - When TMDB Path Is Blank")
    void shouldReturnNullProfileUrlWhenTmdbPathIsBlank() {
        assertThat(TmdbImageUrlBuilder.profileUrl("  ")).isNull();
    }

    @Test
    @DisplayName("Should Normalize Leading Slash - When TMDB Path Has None")
    void shouldNormalizeLeadingSlashWhenTmdbPathHasNone() {
        assertThat(TmdbImageUrlBuilder.posterUrl("alien.jpg"))
                .isEqualTo("https://image.tmdb.org/t/p/w500/alien.jpg");
    }
}
