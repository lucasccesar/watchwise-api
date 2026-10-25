package com.watchwise.watchwise_api.platformimport.service.impl;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class TheaterTagMatcher {

    private static final Set<String> CINEMA_TAGS = Set.of(
            "no cinema", "assisti no cinema", "🍿/assisti no cinema", "theater", "at the cinema",
            "watched at the cinema", "en el cine", "en cines", "vista en cines🍿", "au ciné",
            "dans un ciné", "cinéma", "im kino", "im kino geschaut", "al cinema", "visto al cinema",
            "映画館", "映画館視聴", "映画館 🎟️", "극장", "극장 관람", "影院", "在电影院", "w kinie",
            "w kinie (2025)", "sinemada", "sinemada izledim").stream()
            .map(TheaterTagMatcher::normalize)
            .collect(Collectors.toUnmodifiableSet());

    public boolean matches(List<String> tags) {
        return tags != null && tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(TheaterTagMatcher::normalize)
                .anyMatch(CINEMA_TAGS::contains);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
