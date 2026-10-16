package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameCandidateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyActorReferenceDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyActorViewDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyPlayStateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyWorkViewDTO;
import com.watchwise.watchwise_api.common.exception.DailyGamesUnavailableException;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.service.DailyGameFilmographyEntryKey;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class DailyGameFilmographyPlayAssembler {

    public DailyGameFilmographyPlayStateDTO assemble(
            DailyChallenge challenge,
            List<DailyGameAttemptDTO> attempts,
            boolean majorRoles,
            int maxAttempts) {
        return assemble(challenge, attempts, majorRoles, maxAttempts, false);
    }

    public DailyGameFilmographyPlayStateDTO assemble(
            DailyChallenge challenge,
            List<DailyGameAttemptDTO> attempts,
            boolean majorRoles,
            int maxAttempts,
            boolean terminal) {
        boolean series = challenge.getGameType() == DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY;
        boolean effectiveMajorRoles = series && majorRoles;
        List<DailyGameAttemptDTO> safeAttempts = attempts == null ? List.of() : attempts;
        String keyPrefix = series ? "SERIES:" : "MOVIE:";
        Set<String> revealedWorkKeys = new LinkedHashSet<>();
        List<DailyGameFilmographyActorViewDTO> actorViews = new ArrayList<>();
        Map<String, LinkedHashMap<String, DailyGameFilmographyActorReferenceDTO>> sharedActorsByWorkKey =
                new LinkedHashMap<>();

        for (DailyGameAttemptDTO attempt : safeAttempts) {
            if (attempt == null || attempt.filmographyFeedback() == null) {
                continue;
            }
            DailyGameFilmographyFeedbackDTO feedback = attempt.filmographyFeedback();
            var guessedActor = feedback.guessedActor();
            if (guessedActor == null || guessedActor.personTmdbId() == null) {
                continue;
            }
            DailyGameCandidateDTO candidate = matchingActorCandidate(attempt.candidate(), guessedActor.personTmdbId());
            String profileUrl = candidate == null ? null : candidate.imageUrl();
            actorViews.add(new DailyGameFilmographyActorViewDTO(
                    guessedActor.personTmdbId(), guessedActor.name(),
                    profileUrl,
                    ageAtChallengeDate(candidate == null ? null : candidate.date(), challenge.getChallengeDate()),
                    distinctCount(feedback.sharedAllRoleWorkKeys()),
                    distinctCount(feedback.sharedMajorRoleWorkKeys())));

            List<String> selectedKeys = selectedKeys(feedback, series && majorRoles);
            for (String workKey : selectedKeys) {
                if (workKey == null || !workKey.startsWith(keyPrefix)) {
                    continue;
                }
                revealedWorkKeys.add(workKey);
                if (guessedActor.name() != null) {
                    sharedActorsByWorkKey.computeIfAbsent(workKey, ignored -> new LinkedHashMap<>())
                            .putIfAbsent(guessedActor.personTmdbId(), new DailyGameFilmographyActorReferenceDTO(
                                    guessedActor.personTmdbId(), guessedActor.name(), profileUrl));
                }
            }
        }

        List<DailyGameFilmographyWorkViewDTO> workViews = snapshotEntries(challenge, series, effectiveMajorRoles,
                revealedWorkKeys, sharedActorsByWorkKey, terminal);
        int normalizedMaxAttempts = Math.max(0, maxAttempts);
        int attemptsUsed = safeAttempts.size();
        return new DailyGameFilmographyPlayStateDTO(
                effectiveMajorRoles, actorViews, workViews, attemptsUsed,
                Math.max(0, normalizedMaxAttempts - attemptsUsed), normalizedMaxAttempts);
    }

    private List<DailyGameFilmographyWorkViewDTO> snapshotEntries(
            DailyChallenge challenge,
            boolean series,
            boolean majorRoles,
            Set<String> revealedWorkKeys,
            Map<String, LinkedHashMap<String, DailyGameFilmographyActorReferenceDTO>> sharedActorsByWorkKey,
            boolean terminal) {
        Map<String, Object> snapshot = challenge.getAnswerSnapshot();
        Object rawFilmography = snapshot == null ? null : snapshot.get("filmography");
        if (!(rawFilmography instanceof List<?> entries) || entries.isEmpty()) {
            throw new DailyGamesUnavailableException();
        }

        List<DailyGameFilmographyWorkViewDTO> works = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            Object entry = entries.get(index);
            if (!(entry instanceof Map<?, ?> raw)) {
                continue;
            }
            String workId = string(raw.get("workId"));
            String title = string(raw.get("title"));
            String mediaType = string(raw.get("mediaType"));
            if (workId == null || workId.isBlank() || title == null || title.isBlank()
                    || (series && !"tv".equalsIgnoreCase(mediaType))
                    || (!series && !"movie".equalsIgnoreCase(mediaType))) {
                continue;
            }
            Integer episodeCount = integer(raw.get("episodeCount"));
            Integer totalEpisodes = integer(raw.get("totalEpisodes"));
            if (series && (episodeCount == null || episodeCount < 1
                    || (majorRoles && !isMajorRole(episodeCount, totalEpisodes)))) {
                continue;
            }

            String workKey = (series ? "SERIES:" : "MOVIE:") + workId;
            boolean highlighted = revealedWorkKeys.contains(workKey);
            boolean revealed = terminal || highlighted;
            LinkedHashMap<String, DailyGameFilmographyActorReferenceDTO> sharedActors =
                    sharedActorsByWorkKey.get(workKey);
            works.add(new DailyGameFilmographyWorkViewDTO(
                    revealed ? workId : null,
                    revealed ? title : null,
                    revealed,
                    highlighted,
                    integer(raw.get("year")),
                    strings(raw.get("genres")),
                    revealed ? string(raw.get("posterUrl")) : null,
                    episodeCount,
                    string(raw.get("period")),
                    revealed ? string(raw.get("character")) : null,
                    sharedActors == null ? List.of() : List.copyOf(sharedActors.values()),
                    DailyGameFilmographyEntryKey.forSnapshotEntry(challenge.getId(), index)));
        }
        if (works.isEmpty()) {
            throw new DailyGamesUnavailableException();
        }
        works.sort(Comparator.comparing(DailyGameFilmographyWorkViewDTO::year,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(works);
    }

    private DailyGameCandidateDTO matchingActorCandidate(DailyGameCandidateDTO candidate, String actorId) {
        return candidate != null && Objects.equals(candidate.personTmdbId(), actorId) ? candidate : null;
    }

    private Integer ageAtChallengeDate(LocalDate birthDate, LocalDate challengeDate) {
        if (birthDate == null || challengeDate == null || birthDate.isAfter(challengeDate)) {
            return null;
        }
        return Period.between(birthDate, challengeDate).getYears();
    }

    private List<String> selectedKeys(DailyGameFilmographyFeedbackDTO feedback, boolean majorRoles) {
        return majorRoles ? feedback.sharedMajorRoleWorkKeys() : feedback.sharedAllRoleWorkKeys();
    }

    private int distinctCount(List<String> keys) {
        return (int) keys.stream().filter(Objects::nonNull).distinct().count();
    }

    private boolean isMajorRole(int episodeCount, Integer totalEpisodes) {
        if (totalEpisodes == null || totalEpisodes < 1) {
            return false;
        }
        double ratio = totalEpisodes <= 6 ? .33d : totalEpisodes <= 20 ? .40d : .50d;
        return episodeCount >= (int) Math.ceil(totalEpisodes * ratio);
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<String> strings(Object value) {
        return value instanceof List<?> values
                ? values.stream().map(this::string).filter(Objects::nonNull).toList()
                : List.of();
    }
}
