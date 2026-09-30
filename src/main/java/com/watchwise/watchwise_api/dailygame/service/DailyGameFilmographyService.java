package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public interface DailyGameFilmographyService {

    DailyGameFilmographyFeedbackDTO compare(
            DailyChallenge challenge, String guessedPersonTmdbId, boolean majorRoles);

    DailyGameFilmographyLookupBudget newGenerationBudget();

    FilmographySnapshot snapshot(String personTmdbId, DailyGameType gameType);

    FilmographySnapshot snapshot(
            String personTmdbId, DailyGameType gameType, DailyGameFilmographyLookupBudget budget);

    boolean hasAtLeastEntries(
            FilmographySnapshot snapshot, DailyGameType gameType, boolean majorRoles, int minimumEntries);

    record FilmographySnapshot(List<Map<String, Object>> entries) {

        public FilmographySnapshot {
            entries = entries == null ? List.of() : entries.stream()
                    .map(entry -> Collections.unmodifiableMap(new LinkedHashMap<>(entry)))
                    .toList();
        }
    }
}
