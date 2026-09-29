package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameActorGuessDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameCandidateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameComparisonCellDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameComparisonDirection;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameComparisonStatus;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyEntryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameGuessFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameInfoFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DailyGameAttemptDetailsCodec {

    private static final String ATTEMPTS_KEY = "attempts";

    public List<DailyGameAttemptDTO> read(Map<String, Object> details) {
        if (details == null || !(details.get(ATTEMPTS_KEY) instanceof List<?> rawAttempts)) {
            return List.of();
        }
        List<DailyGameAttemptDTO> attempts = rawAttempts.stream()
                .map(this::decodeAttempt)
                .filter(java.util.Objects::nonNull)
                .toList();
        return List.copyOf(attempts);
    }

    public Map<String, Object> append(Map<String, Object> details, DailyGameAttemptDTO attempt) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        if (details != null) {
            details.forEach((key, value) -> encoded.put(key, copyValue(value)));
        }
        List<Object> attempts = new ArrayList<>();
        if (details != null && details.get(ATTEMPTS_KEY) instanceof List<?> existingAttempts) {
            existingAttempts.forEach(value -> attempts.add(copyValue(value)));
        }
        attempts.add(encodeAttempt(attempt));
        encoded.put(ATTEMPTS_KEY, List.copyOf(attempts));
        return Collections.unmodifiableMap(encoded);
    }

    private Map<String, Object> encodeAttempt(DailyGameAttemptDTO attempt) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("attemptNumber", attempt.attemptNumber());
        encoded.put("candidate", encodeCandidate(attempt.candidate()));
        encoded.put("episodeFeedback", encodeEpisodeFeedback(attempt.episodeFeedback()));
        encoded.put("infoFeedback", encodeInfoFeedback(attempt.infoFeedback()));
        encoded.put("filmographyFeedback", encodeFilmographyFeedback(attempt.filmographyFeedback()));
        return encoded;
    }

    private Map<String, Object> encodeCandidate(DailyGameCandidateDTO candidate) {
        if (candidate == null) {
            return null;
        }
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("targetKind", enumName(candidate.targetKind()));
        encoded.put("tmdbId", candidate.tmdbId());
        encoded.put("personTmdbId", candidate.personTmdbId());
        encoded.put("seriesTmdbId", candidate.seriesTmdbId());
        encoded.put("seasonNumber", candidate.seasonNumber());
        encoded.put("episodeNumber", candidate.episodeNumber());
        encoded.put("title", candidate.title());
        encoded.put("imageUrl", candidate.imageUrl());
        encoded.put("date", candidate.date() == null ? null : candidate.date().toString());
        return encoded;
    }

    private Map<String, Object> encodeEpisodeFeedback(DailyGameGuessFeedbackDTO feedback) {
        if (feedback == null) {
            return null;
        }
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("seriesCorrect", feedback.seriesCorrect());
        encoded.put("seasonCorrect", feedback.seasonCorrect());
        encoded.put("episodeCorrect", feedback.episodeCorrect());
        encoded.put("exactMatch", feedback.exactMatch());
        return encoded;
    }

    private Map<String, Object> encodeInfoFeedback(DailyGameInfoFeedbackDTO feedback) {
        if (feedback == null) {
            return null;
        }
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("platforms", encodeCell(feedback.platforms()));
        encoded.put("genres", encodeCell(feedback.genres()));
        encoded.put("year", encodeCell(feedback.year()));
        encoded.put("certification", encodeCell(feedback.certification()));
        encoded.put("directorOrCreators", encodeCell(feedback.directorOrCreators()));
        encoded.put("cast", encodeCell(feedback.cast()));
        encoded.put("productionCompanies", encodeCell(feedback.productionCompanies()));
        encoded.put("revenueOrSeasons", encodeCell(feedback.revenueOrSeasons()));
        return encoded;
    }

    private Map<String, Object> encodeCell(DailyGameComparisonCellDTO cell) {
        if (cell == null) {
            return null;
        }
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("status", enumName(cell.status()));
        encoded.put("direction", enumName(cell.direction()));
        encoded.put("displayValue", cell.displayValue());
        encoded.put("matchedValues", cell.matchedValues());
        encoded.put("matchCount", cell.matchCount());
        return encoded;
    }

    private Map<String, Object> encodeFilmographyFeedback(DailyGameFilmographyFeedbackDTO feedback) {
        if (feedback == null) {
            return null;
        }
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("guessedActor", encodeActorGuess(feedback.guessedActor()));
        encoded.put("sharedMajorRoleWorkKeys", feedback.sharedMajorRoleWorkKeys());
        encoded.put("sharedAllRoleWorkKeys", feedback.sharedAllRoleWorkKeys());
        encoded.put("entries", feedback.entries().stream().map(this::encodeFilmographyEntry).toList());
        return encoded;
    }

    private Map<String, Object> encodeActorGuess(DailyGameActorGuessDTO actor) {
        if (actor == null) {
            return null;
        }
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("personTmdbId", actor.personTmdbId());
        encoded.put("name", actor.name());
        return encoded;
    }

    private Map<String, Object> encodeFilmographyEntry(DailyGameFilmographyEntryDTO entry) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("workId", entry.workId());
        encoded.put("title", entry.title());
        encoded.put("revealed", entry.revealed());
        encoded.put("highlighted", entry.highlighted());
        encoded.put("year", entry.year());
        encoded.put("genres", entry.genres());
        encoded.put("posterUrl", entry.posterUrl());
        encoded.put("episodeCount", entry.episodeCount());
        encoded.put("period", entry.period());
        encoded.put("character", entry.character());
        return encoded;
    }

    private DailyGameAttemptDTO decodeAttempt(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> attempt = stringKeyMap(raw);
        DailyGameCandidateDTO candidate = decodeCandidate(attempt.get("candidate"));
        return new DailyGameAttemptDTO(
                integer(attempt.get("attemptNumber"), 0),
                candidate,
                decodeEpisodeFeedback(attempt.get("episodeFeedback")),
                decodeInfoFeedback(attempt.get("infoFeedback")),
                decodeFilmographyFeedback(attempt.get("filmographyFeedback")));
    }

    private DailyGameCandidateDTO decodeCandidate(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> candidate = stringKeyMap(raw);
        return new DailyGameCandidateDTO(
                enumValue(DailyGameTargetKind.class, candidate.get("targetKind")),
                string(candidate.get("tmdbId")),
                string(candidate.get("personTmdbId")),
                string(candidate.get("seriesTmdbId")),
                nullableInteger(candidate.get("seasonNumber")),
                nullableInteger(candidate.get("episodeNumber")),
                string(candidate.get("title")),
                string(candidate.get("imageUrl")),
                candidate.get("date") == null ? null : LocalDate.parse(string(candidate.get("date"))));
    }

    private DailyGameGuessFeedbackDTO decodeEpisodeFeedback(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> feedback = stringKeyMap(raw);
        return new DailyGameGuessFeedbackDTO(
                bool(feedback.get("seriesCorrect")),
                bool(feedback.get("seasonCorrect")),
                bool(feedback.get("episodeCorrect")),
                bool(feedback.get("exactMatch")));
    }

    private DailyGameInfoFeedbackDTO decodeInfoFeedback(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> feedback = stringKeyMap(raw);
        return new DailyGameInfoFeedbackDTO(
                decodeCell(feedback.get("platforms")), decodeCell(feedback.get("genres")),
                decodeCell(feedback.get("year")), decodeCell(feedback.get("certification")),
                decodeCell(feedback.get("directorOrCreators")), decodeCell(feedback.get("cast")),
                decodeCell(feedback.get("productionCompanies")), decodeCell(feedback.get("revenueOrSeasons")));
    }

    private DailyGameComparisonCellDTO decodeCell(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> cell = stringKeyMap(raw);
        return new DailyGameComparisonCellDTO(
                enumValue(DailyGameComparisonStatus.class, cell.get("status")),
                enumValue(DailyGameComparisonDirection.class, cell.get("direction")),
                cell.get("displayValue"),
                strings(cell.get("matchedValues")),
                nullableInteger(cell.get("matchCount")));
    }

    private DailyGameFilmographyFeedbackDTO decodeFilmographyFeedback(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> feedback = stringKeyMap(raw);
        List<DailyGameFilmographyEntryDTO> entries = feedback.get("entries") instanceof List<?> rawEntries
                ? rawEntries.stream().map(this::decodeFilmographyEntry)
                .filter(java.util.Objects::nonNull).toList() : List.of();
        return new DailyGameFilmographyFeedbackDTO(
                decodeActorGuess(feedback.get("guessedActor")),
                strings(feedback.get("sharedMajorRoleWorkKeys")),
                strings(feedback.get("sharedAllRoleWorkKeys")), entries);
    }

    private DailyGameActorGuessDTO decodeActorGuess(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> actor = stringKeyMap(raw);
        return new DailyGameActorGuessDTO(string(actor.get("personTmdbId")), string(actor.get("name")));
    }

    private DailyGameFilmographyEntryDTO decodeFilmographyEntry(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> entry = stringKeyMap(raw);
        return new DailyGameFilmographyEntryDTO(
                string(entry.get("workId")), string(entry.get("title")), bool(entry.get("revealed")),
                bool(entry.get("highlighted")), nullableInteger(entry.get("year")),
                strings(entry.get("genres")), string(entry.get("posterUrl")),
                nullableInteger(entry.get("episodeCount")), string(entry.get("period")),
                string(entry.get("character")));
    }

    private Object copyValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> copy = new LinkedHashMap<>();
            raw.forEach((key, nested) -> copy.put(String.valueOf(key), copyValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> raw) {
            return List.copyOf(raw.stream().map(this::copyValue).toList());
        }
        return value;
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> converted = new LinkedHashMap<>();
        source.forEach((key, value) -> converted.put(String.valueOf(key), value));
        return converted;
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean bool(Object value) {
        return value instanceof Boolean booleanValue && booleanValue;
    }

    private int integer(Object value, int fallback) {
        Integer result = nullableInteger(value);
        return result == null ? fallback : result;
    }

    private Integer nullableInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().map(this::string).filter(java.util.Objects::nonNull).toList();
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, String.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
