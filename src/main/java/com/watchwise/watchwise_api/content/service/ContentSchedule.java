package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ContentSchedule(
        ContentScheduleKey key,
        LocalDate releaseDate,
        String externalStatus,
        List<ContentScheduleEpisode> episodes,
        boolean complete,
        boolean releaseDateLookupUnavailable,
        String title,
        String posterPath,
        Map<Integer, Integer> expectedEpisodeCountsBySeason) {

    public ContentSchedule {
        Objects.requireNonNull(key, "key is required");
        episodes = episodes == null ? List.of() : List.copyOf(episodes);
        expectedEpisodeCountsBySeason = expectedEpisodeCountsBySeason == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(expectedEpisodeCountsBySeason));
    }

    public ContentSchedule(
            ContentScheduleKey key,
            LocalDate releaseDate,
            String externalStatus,
            List<ContentScheduleEpisode> episodes,
            boolean complete,
            boolean releaseDateLookupUnavailable) {
        this(key, releaseDate, externalStatus, episodes, complete, releaseDateLookupUnavailable, null, null, Map.of());
    }

    public ContentType type() {
        return key.type();
    }

    public String tmdbId() {
        return key.type() == com.watchwise.watchwise_api.content.entity.ContentType.SEASON ? null : key.tmdbId();
    }

    public String seriesTmdbId() {
        return key.type() == com.watchwise.watchwise_api.content.entity.ContentType.SERIES
                ? key.tmdbId()
                : key.seriesTmdbId();
    }

    public Integer seasonNumber() {
        return key.seasonNumber();
    }

    public boolean releaseDatesUnavailable() {
        return releaseDateLookupUnavailable;
    }
}
