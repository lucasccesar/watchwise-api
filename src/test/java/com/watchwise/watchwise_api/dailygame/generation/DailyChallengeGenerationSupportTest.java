package com.watchwise.watchwise_api.dailygame.generation;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DailyChallengeGenerationSupportTest {

    @Test
    void shouldGenerateDiscoveryPagesOnlyBetweenOneAndTwenty() {
        assertThat(IntStream.range(0, 500)
                .mapToObj(ignored -> DailyChallengeGenerationSupport.randomPage())
                .allMatch(page -> page >= 1 && page <= 20))
                .isTrue();
    }

    @Test
    void shouldRejectAsianOriginalLanguagesAfterNormalizingLocaleSuffix() {
        assertThat(DailyChallengeGenerationSupport.isAsianOriginalLanguage("hi")).isTrue();
        assertThat(DailyChallengeGenerationSupport.isAsianOriginalLanguage("ja-JP")).isTrue();
        assertThat(DailyChallengeGenerationSupport.isAsianOriginalLanguage("zh_CN")).isTrue();
        assertThat(DailyChallengeGenerationSupport.isAsianOriginalLanguage("en-US")).isFalse();
        assertThat(DailyChallengeGenerationSupport.isAsianOriginalLanguage(null)).isFalse();
    }
}
