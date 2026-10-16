package com.watchwise.watchwise_api.dailygame.dto;

import java.util.List;

public record DailyGameFilmographyEntryDTO(
        String workId,
        String title,
        boolean revealed,
        boolean highlighted,
        Integer year,
        List<String> genres,
        String posterUrl,
        Integer episodeCount,
        String period,
        String character,
        String entryKey) {

    public DailyGameFilmographyEntryDTO(
            String workId,
            String title,
            boolean revealed,
            boolean highlighted,
            Integer year,
            List<String> genres,
            String posterUrl,
            Integer episodeCount,
            String period,
            String character) {
        this(workId, title, revealed, highlighted, year, genres, posterUrl, episodeCount, period, character, null);
    }

    public DailyGameFilmographyEntryDTO {
        genres = genres == null ? List.of() : List.copyOf(genres);
    }

    public String workTmdbId() {
        return workId;
    }
}
