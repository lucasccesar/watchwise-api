package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.DailyGamesUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.dailygame.dto.*;
import com.watchwise.watchwise_api.dailygame.entity.*;
import com.watchwise.watchwise_api.dailygame.media.*;
import com.watchwise.watchwise_api.dailygame.repository.*;
import com.watchwise.watchwise_api.dailygame.service.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class DailyGamePlayServiceImpl implements DailyGamePlayService {
    private static final List<DailyGameImageVariant> BLURS = List.of(
            DailyGameImageVariant.BLUR_26, DailyGameImageVariant.BLUR_20,
            DailyGameImageVariant.BLUR_14, DailyGameImageVariant.BLUR_9,
            DailyGameImageVariant.BLUR_5, DailyGameImageVariant.BLUR_2);
    private static final DailyGameMediaSlot[] FRAMES = {
            DailyGameMediaSlot.FRAME_0, DailyGameMediaSlot.FRAME_1,
            DailyGameMediaSlot.FRAME_2, DailyGameMediaSlot.FRAME_3,
            DailyGameMediaSlot.FRAME_4, DailyGameMediaSlot.FRAME_5};

    private final DailyChallengeRepository challenges;
    private final UserDailyGameResultRepository results;
    private final DailyGameServiceImpl mutations;
    private final DailyGameMediaService media;
    private final DailyGameAttemptDetailsCodec codec;
    private final DailyGameFilmographyPlayAssembler filmographyAssembler;
    private final Clock clock;
    private final DailyGamePlayAssembler assembler = new DailyGamePlayAssembler();
    private final DailyGameInfoPlayAssembler infoAssembler = new DailyGameInfoPlayAssembler();

    public DailyGamePlayServiceImpl(DailyChallengeRepository challenges, UserDailyGameResultRepository results,
            DailyGameServiceImpl mutations, DailyGameMediaService media,
            DailyGameAttemptDetailsCodec codec, DailyGameFilmographyPlayAssembler filmographyAssembler, Clock clock) {
        this.challenges = challenges;
        this.results = results;
        this.mutations = mutations;
        this.media = media;
        this.codec = codec;
        this.filmographyAssembler = filmographyAssembler;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public DailyGamePlayViewDTO readToday(UUID userId, DailyGameType gameType) {
        return readToday(userId, gameType, true);
    }

    @Override
    @Transactional(readOnly = true)
    public DailyGamePlayViewDTO readToday(UUID userId, DailyGameType gameType, boolean majorRoles) {
        requireGameType(gameType);
        LocalDate today = LocalDate.now(clock);
        DailyChallenge challenge = challenges.findByChallengeDateAndGameType(today, gameType)
                .orElseThrow(DailyGamesUnavailableException::new);
        UserDailyGameResult result = results.findByUserIdAndDailyChallengeIdIn(userId, List.of(challenge.getId()))
                .stream().findFirst().orElse(null);
        return view(userId, challenge, result,
                result == null ? List.of() : codec.read(result.getAttemptDetails()), majorRoles);
    }

    @Override
    public DailyGamePlayActionResponseDTO submitToday(UUID userId, DailyGameType gameType,
            DailyGameAttemptRequest request) {
        return submitToday(userId, gameType, request, true);
    }

    @Override
    public DailyGamePlayActionResponseDTO submitToday(UUID userId, DailyGameType gameType,
            DailyGameAttemptRequest request, boolean majorRoles) {
        requireGameType(gameType);
        LocalDate challengeDate = LocalDate.now(clock);
        preflight(challengeDate, gameType, majorRoles);
        var mutation = mutations.mutateAttempt(userId, challengeDate, gameType, request, majorRoles, true);
        return new DailyGamePlayActionResponseDTO(view(userId, mutation.challenge(), mutation.result(),
                mutation.attempts(), majorRoles), attempt(mutation.challenge(), mutation.currentAttempt()));
    }

    @Override
    public DailyGamePlayActionResponseDTO giveUpToday(UUID userId, DailyGameType gameType) {
        return giveUpToday(userId, gameType, true);
    }

    @Override
    public DailyGamePlayActionResponseDTO giveUpToday(UUID userId, DailyGameType gameType, boolean majorRoles) {
        requireGameType(gameType);
        LocalDate challengeDate = LocalDate.now(clock);
        preflight(challengeDate, gameType, majorRoles);
        var mutation = mutations.mutateGiveUp(userId, challengeDate, gameType);
        return new DailyGamePlayActionResponseDTO(view(userId, mutation.challenge(), mutation.result(),
                mutation.attempts(), majorRoles), null);
    }

    private DailyGamePlayViewDTO view(UUID userId, DailyChallenge challenge, UserDailyGameResult result,
            List<DailyGameAttemptDTO> details, boolean majorRoles) {
        int used = result == null ? 0 : result.getAttemptsUsed();
        DailyGameViewStatus status = result == null ? DailyGameViewStatus.NOT_PLAYED
                : DailyGameViewStatus.valueOf(result.getStatus().name());
        boolean terminal = status == DailyGameViewStatus.COMPLETED || status == DailyGameViewStatus.FAILED;
        List<DailyGameMediaRefDTO> refs = media(userId, challenge, used, terminal);
        List<DailyGameAttemptViewDTO> attempts = details.stream().map(detail -> attempt(challenge, detail)).toList();
        DailyGameAnswerPreviewDTO answer = terminal ? answer(userId, challenge, refs) : null;
        DailyGameFilmographyPlayStateDTO filmography = isFilmographyGame(challenge.getGameType())
                ? filmography(challenge, details, used, majorRoles, terminal) : null;
        LocalDate date = challenge.getChallengeDate();
        return assembler.assemble(new DailyGamePlayProjection(date,
                date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), challenge.getGameType(),
                challenge.getTargetKind(), challenge.getGameType().maxAttempts(), used, status,
                result == null ? 0 : result.getScore(), refs, attempts, answer,
                result != null && result.isShareOnCompletion(), result != null && result.getSharedAt() != null,
                filmography));
    }

    private DailyGameFilmographyPlayStateDTO filmography(
            DailyChallenge challenge, List<DailyGameAttemptDTO> details, int attemptsUsed,
            boolean majorRoles, boolean terminal) {
        DailyGameFilmographyPlayStateDTO assembled = filmographyAssembler.assemble(
                challenge, details, majorRoles, challenge.getGameType().maxAttempts(),
                terminal);
        int maxAttempts = challenge.getGameType().maxAttempts();
        return new DailyGameFilmographyPlayStateDTO(assembled.majorRoles(), assembled.guessedActors(),
                assembled.entries(), attemptsUsed, Math.max(0, maxAttempts - attemptsUsed), maxAttempts);
    }

    private void preflight(LocalDate challengeDate, DailyGameType gameType, boolean majorRoles) {
        DailyChallenge challenge = challenges.findByChallengeDateAndGameType(challengeDate, gameType)
                .orElseThrow(DailyGamesUnavailableException::new);
        try {
            TmdbClient.normalizeImagePath(challenge.getImagePath());
            if (gameType == DailyGameType.EPISODE_BY_FRAME
                    && DailyGameFramePaths.select(challenge.getDisplaySnapshot()).size() != FRAMES.length) {
                throw new DailyGamesUnavailableException();
            }
            if (isFilmographyGame(gameType)) {
                filmographyAssembler.assemble(challenge, List.of(), majorRoles,
                        gameType.maxAttempts(), false);
            }
            String seriesPoster = seriesPosterPath(challenge.getAnswerSnapshot());
            if (seriesPoster != null) {
                TmdbClient.normalizeImagePath(seriesPoster);
            }
        } catch (BadRequestException invalidImage) {
            throw new DailyGamesUnavailableException();
        }
    }

    private String seriesPosterPath(Map<String, Object> snapshot) {
        if (snapshot == null) return null;
        Object presentation = snapshot.get("presentation");
        if (presentation instanceof Map<?, ?> map
                && map.get("seriesPosterPath") instanceof String path && !path.isBlank()) {
            return path;
        }
        return snapshot.get("seriesPosterPath") instanceof String path && !path.isBlank() ? path : null;
    }

    private List<DailyGameMediaRefDTO> media(UUID userId, DailyChallenge challenge, int used, boolean terminal) {
        if (isInformationOrFilmographyGame(challenge.getGameType())) {
            if (!terminal) return List.of();
            return List.of(media.issue(userId, challenge.getId(), DailyGameMediaSlot.CLUE,
                    DailyGameImageVariant.ORIGINAL, challenge.getImagePath()));
        }
        if (challenge.getGameType() != DailyGameType.EPISODE_BY_FRAME) {
            DailyGameImageVariant variant = terminal ? DailyGameImageVariant.ORIGINAL
                    : BLURS.get(Math.min(used, BLURS.size() - 1));
            return List.of(media.issue(userId, challenge.getId(), DailyGameMediaSlot.CLUE,
                    variant, challenge.getImagePath()));
        }
        List<String> paths = DailyGameFramePaths.select(challenge.getDisplaySnapshot());
        if (paths.size() != FRAMES.length) throw new DailyGamesUnavailableException();
        List<DailyGameMediaRefDTO> refs = new ArrayList<>(FRAMES.length);
        for (int index = 0; index < FRAMES.length; index++) {
            if (terminal || index <= used) {
                refs.add(media.issue(userId, challenge.getId(), FRAMES[index],
                        DailyGameImageVariant.ORIGINAL, paths.get(index)));
            } else {
                refs.add(new DailyGameMediaRefDTO(FRAMES[index], null, DailyGameImageVariant.ORIGINAL, false));
            }
        }
        return List.copyOf(refs);
    }

    private DailyGameAttemptViewDTO attempt(DailyChallenge challenge, DailyGameAttemptDTO detail) {
        if (detail == null) return null;
        DailyGameCandidateDTO candidate = detail.candidate();
        DailyGameCandidateViewDTO view = candidate == null ? null : new DailyGameCandidateViewDTO(
                candidate.targetKind(), candidate.tmdbId(), candidate.personTmdbId(), candidate.seriesTmdbId(),
                candidate.seasonNumber(), candidate.episodeNumber(), candidate.title(), candidate.imageUrl(),
                candidate.date() == null ? null : candidate.date().getYear(), List.of(), null);
        DailyGameGuessFeedbackDTO feedback = detail.episodeFeedback();
        boolean correct = candidate != null && switch (challenge.getTargetKind()) {
            case MOVIE, SERIES -> Objects.equals(challenge.getTargetTmdbId(), candidate.tmdbId());
            case PERSON -> Objects.equals(challenge.getTargetTmdbId(), candidate.personTmdbId());
            case EPISODE -> Objects.equals(challenge.getSeriesTmdbId(), candidate.seriesTmdbId())
                    && Objects.equals(challenge.getSeasonNumber(), candidate.seasonNumber())
                    && Objects.equals(challenge.getEpisodeNumber(), candidate.episodeNumber());
        };
        DailyGameAttemptViewDTO attemptView = new DailyGameAttemptViewDTO(detail.attemptNumber(), view,
                new DailyGameAttemptOutcomeDTO(correct,
                        feedback == null ? null : feedback.seriesCorrect(),
                        feedback == null ? null : feedback.seasonCorrect(),
                        feedback == null ? null : feedback.episodeCorrect()));
        return infoAssembler.assemble(attemptView,
                isInformationGame(challenge.getGameType()) ? detail.infoFeedback() : null);
    }

    private DailyGameAnswerPreviewDTO answer(UUID userId, DailyChallenge challenge,
            List<DailyGameMediaRefDTO> refs) {
        Map<String, Object> snapshot = challenge.getAnswerSnapshot();
        Map<?, ?> presentation = snapshot != null && snapshot.get("presentation") instanceof Map<?, ?> map
                ? map : Map.of();
        DailyGameTargetKind kind = challenge.getTargetKind();
        String title = first(presentation, "title", "name", "episodeName");
        if (title == null) title = first(snapshot, "title", "episodeName");
        String posterPath = first(presentation, "seriesPosterPath");
        if (posterPath == null) posterPath = first(snapshot, "seriesPosterPath");
        DailyGameMediaRefDTO poster = posterPath == null ? null : media.issue(userId, challenge.getId(),
                DailyGameMediaSlot.SERIES_POSTER, DailyGameImageVariant.ORIGINAL, posterPath);
        Integer year = number(presentation.get("year"));
        return new DailyGameAnswerPreviewDTO(kind,
                kind == DailyGameTargetKind.MOVIE || kind == DailyGameTargetKind.SERIES
                        ? challenge.getTargetTmdbId() : null,
                kind == DailyGameTargetKind.PERSON ? challenge.getTargetTmdbId() : null,
                kind == DailyGameTargetKind.EPISODE ? challenge.getSeriesTmdbId() : null,
                challenge.getSeasonNumber(), challenge.getEpisodeNumber(), title, refs.getFirst(), year,
                strings(presentation.get("genres")), first(presentation, "director"),
                strings(presentation.get("creators")), strings(presentation.get("knownForTitles")),
                first(presentation, "seriesName") != null ? first(presentation, "seriesName")
                        : first(snapshot, "seriesName"), poster,
                number(presentation.get("seriesYear")) != null ? number(presentation.get("seriesYear"))
                        : number(snapshot == null ? null : snapshot.get("seriesYear")),
                parseDate(presentation.get("airDate")));
    }

    private String first(Map<?, ?> values, String... keys) {
        if (values == null) return null;
        for (String key : keys) {
            Object value = values.get(key);
            if (value instanceof String text && !text.isBlank()) return text;
        }
        return null;
    }

    private Integer number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : Integer.valueOf(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private LocalDate parseDate(Object value) {
        try { return value == null ? null : LocalDate.parse(value.toString()); }
        catch (RuntimeException ignored) { return null; }
    }

    private void requireGameType(DailyGameType gameType) {
        if (gameType == null) throw new BadRequestException("gameType is required");
    }

    private boolean isInformationGame(DailyGameType gameType) {
        return gameType == DailyGameType.MOVIE_BY_INFO || gameType == DailyGameType.SERIES_BY_INFO;
    }

    private boolean isFilmographyGame(DailyGameType gameType) {
        return gameType == DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY
                || gameType == DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY;
    }

    private boolean isInformationOrFilmographyGame(DailyGameType gameType) {
        return isInformationGame(gameType) || isFilmographyGame(gameType);
    }
}
