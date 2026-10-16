package com.watchwise.watchwise_api.dailygame.dto;

import java.util.List;

public record DailyGameFilmographyWorkViewDTO(
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
        List<DailyGameFilmographyActorReferenceDTO> sharedWith,
        String entryKey) {

    public DailyGameFilmographyWorkViewDTO(
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
            List<DailyGameFilmographyActorReferenceDTO> sharedWith) {
        this(workId, title, revealed, highlighted, year, genres, posterUrl, episodeCount,
                period, character, sharedWith, null);
    }

    public DailyGameFilmographyWorkViewDTO {
        genres = genres == null ? List.of() : List.copyOf(genres);
        sharedWith = sharedWith == null ? List.of() : List.copyOf(sharedWith);
    }
}
