package com.watchwise.watchwise_api.platformimport.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImportIdentityNormalizerTest {
    private final ImportIdentityNormalizer normalizer = new ImportIdentityNormalizer();

    @Test
    @DisplayName("normalizeTitle Should Fold Case And Whitespace - When Titles Have Formatting Differences")
    void normalizeTitleShouldFoldCaseAndWhitespaceWhenTitlesHaveFormattingDifferences() {
        assertThat(normalizer.normalizeTitle("  The\t  Film\nTitle ")).isEqualTo("the film title");
    }

    @Test
    @DisplayName("normalizeTitle Should Preserve Unicode Punctuation And Diacritics - When Titles Differ Meaningfully")
    void normalizeTitleShouldPreserveUnicodePunctuationAndDiacriticsWhenTitlesDifferMeaningfully() {
        assertThat(normalizer.normalizeTitle("Amélie!")).isNotEqualTo(normalizer.normalizeTitle("Amelie!"));
        assertThat(normalizer.normalizeTitle("A:B")).isNotEqualTo(normalizer.normalizeTitle("AB"));
    }

    @Test
    @DisplayName("canonicalSourceId Should Preserve Case And Trim Boundaries - When Source Id Is Letterboxd Shortcode")
    void canonicalSourceIdShouldPreserveCaseAndTrimBoundariesWhenSourceIdIsLetterboxdShortcode() {
        assertThat(normalizer.canonicalSourceId(" AbC12 ")).isEqualTo("AbC12");
        assertThat(normalizer.canonicalSourceId("AbC12")).isNotEqualTo(normalizer.canonicalSourceId("abc12"));
    }
}
