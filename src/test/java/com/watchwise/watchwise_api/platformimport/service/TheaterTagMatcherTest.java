package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.service.impl.TheaterTagMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TheaterTagMatcherTest {

    private final TheaterTagMatcher matcher = new TheaterTagMatcher();

    @Test
    void matchesTheCompleteMultilingualCinemaTagSetIgnoringCaseAndWhitespace() {
        List<String> tags = List.of(
                "no cinema", "assisti no cinema", "🍿/assisti no cinema", "theater",
                "at the cinema", "watched at the cinema", "en el cine", "en cines", "vista en cines🍿",
                "au ciné", "dans un ciné", "cinéma", "im kino", "im kino geschaut", "al cinema",
                "visto al cinema", "映画館", "映画館視聴", "映画館 🎟️", "극장", "극장 관람", "影院", "在电影院",
                "w kinie", "w kinie (2025)", "sinemada", "sinemada izledim");

        assertThat(matcher.matches(tags)).isTrue();
        assertThat(matcher.matches(List.of("  THEATER  ", "ASSISTI   NO CINEMA"))).isTrue();
    }

    @Test
    void doesNotMatchCinemaWordsEmbeddedInsideAnotherTag() {
        assertThat(matcher.matches(List.of("não assisti no cinema", "best-theater", "cinema vibes"))).isFalse();
    }

    @Test
    void handlesMissingOrEmptyTags() {
        assertThat(matcher.matches(null)).isFalse();
        assertThat(matcher.matches(List.of())).isFalse();
    }
}
