package com.watchwise.watchwise_api.calendar.service;

import java.time.Instant;
import java.util.List;

public record CalendarSeasonSchedule(
        String seriesTmdbId,
        Integer seasonNumber,
        String region,
        String language,
        String seriesTitle,
        String posterPath,
        List<CalendarEpisodeSchedule> episodes) {

    public CalendarSeasonSchedule {
        episodes = List.copyOf(episodes);
    }

    public List<Integer> episodeCoordinates() {
        return episodes.stream().map(CalendarEpisodeSchedule::episodeNumber).toList();
    }

    public CalendarSeasonSchedule withCheckTimes(Instant checkedAt, CheckTimeResolver resolver) {
        return new CalendarSeasonSchedule(seriesTmdbId, seasonNumber, region, language, seriesTitle, posterPath,
                episodes.stream()
                        .map(episode -> episode.withCheckTimes(checkedAt, resolver.nextCheckAt(episode.releaseDate())))
                        .toList());
    }

    @FunctionalInterface
    public interface CheckTimeResolver {
        Instant nextCheckAt(java.time.LocalDate releaseDate);
    }
}
