package com.watchwise.watchwise_api.platformimport.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImportIdentityNormalizerTest {
    private final ImportIdentityNormalizer normalizer = new ImportIdentityNormalizer();

    @Test
    @DisplayName("[normalizeTitle] Should Fold Case And Whitespace - When Titles Have Formatting Differences")
    void normalizeTitleShouldFoldCaseAndWhitespaceWhenTitlesHaveFormattingDifferences() {
        assertThat(normalizer.normalizeTitle("  The\t  Film\nTitle ")).isEqualTo("the film title");
    }

    @Test
    @DisplayName("[normalizeTitle] Should Preserve Unicode Punctuation And Diacritics - When Titles Differ Meaningfully")
    void normalizeTitleShouldPreserveUnicodePunctuationAndDiacriticsWhenTitlesDifferMeaningfully() {
        assertThat(normalizer.normalizeTitle("Amélie!")).isNotEqualTo(normalizer.normalizeTitle("Amelie!"));
        assertThat(normalizer.normalizeTitle("A:B")).isNotEqualTo(normalizer.normalizeTitle("AB"));
    }

    @Test
    @DisplayName("[canonicalSourceId] Should Preserve Input Exactly - When Source Id Is A Letterboxd Shortcode")
    void canonicalSourceIdShouldPreserveInputExactlyWhenSourceIdIsALetterboxdShortcode() {
        assertThat(normalizer.canonicalSourceId(" AbC12 ")).isEqualTo(" AbC12 ");
        assertThat(normalizer.canonicalSourceId("AbC12")).isNotEqualTo(normalizer.canonicalSourceId("abc12"));
    }

    @Test
    @DisplayName("[canonicalSourceId] Should Normalize Only Scheme And Host - When Source Id Is An Absolute Letterboxd URL")
    void canonicalSourceIdShouldNormalizeOnlySchemeAndHostWhenSourceIdIsAnAbsoluteLetterboxdUrl() {
        assertThat(normalizer.canonicalSourceId("HTTPS://LETTERBOXD.COM/Film/AbC12?Token=XyZ#Part"))
                .isEqualTo("https://letterboxd.com/Film/AbC12?Token=XyZ#Part");
    }

    @Test
    @DisplayName("[normalizeTitle] Should Collapse Unicode Whitespace - When Title Contains Nonbreaking And Em Spaces")
    void normalizeTitleShouldCollapseUnicodeWhitespaceWhenTitleContainsNonbreakingAndEmSpaces() {
        assertThat(normalizer.normalizeTitle("  Film\u00a0\u2003Title  ")).isEqualTo("film title");
    }
}
