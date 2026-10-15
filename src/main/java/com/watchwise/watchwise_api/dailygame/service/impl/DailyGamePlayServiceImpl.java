package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.DailyGamesUnavailableException;
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
    private final Clock clock;
    private final DailyGamePlayAssembler assembler = new DailyGamePlayAssembler();

    public DailyGamePlayServiceImpl(DailyChallengeRepository challenges, UserDailyGameResultRepository results,
            DailyGameServiceImpl mutations, DailyGameMediaService media,
            DailyGameAttemptDetailsCodec codec, Clock clock) {
        this.challenges = challenges;
        this.results = results;
        this.mutations = mutations;
        this.media = media;
        this.codec = codec;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public DailyGamePlayViewDTO readToday(UUID userId, DailyGameType gameType) {
        requireVisual(gameType);
        LocalDate today = LocalDate.now(clock);
        DailyChallenge challenge = challenges.findByChallengeDateAndGameType(today, gameType)
                .orElseThrow(DailyGamesUnavailableException::new);
        UserDailyGameResult result = results.findByUserIdAndDailyChallengeIdIn(userId, List.of(challenge.getId()))
                .stream().findFirst().orElse(null);
        return view(userId, challenge, result,
                result == null ? List.of() : codec.read(result.getAttemptDetails()));
    }

    @Override
    public DailyGamePlayActionResponseDTO submitToday(UUID userId, DailyGameType gameType,
            DailyGameAttemptRequest request) {
        requireVisual(gameType);
        var mutation = mutations.mutateAttempt(userId, LocalDate.now(clock), gameType, request, true, true);
        return new DailyGamePlayActionResponseDTO(view(userId, mutation.challenge(), mutation.result(),
                mutation.attempts()), attempt(mutation.challenge(), mutation.currentAttempt()));
    }

    @Override
    public DailyGamePlayActionResponseDTO giveUpToday(UUID userId, DailyGameType gameType) {
        requireVisual(gameType);
        var mutation = mutations.mutateGiveUp(userId, LocalDate.now(clock), gameType);
        return new DailyGamePlayActionResponseDTO(view(userId, mutation.challenge(), mutation.result(),
                mutation.attempts()), null);
    }

    private DailyGamePlayViewDTO view(UUID userId, DailyChallenge challenge, UserDailyGameResult result,
            List<DailyGameAttemptDTO> details) {
        int used = result == null ? 0 : result.getAttemptsUsed();
        DailyGameViewStatus status = result == null ? DailyGameViewStatus.NOT_PLAYED
                : DailyGameViewStatus.valueOf(result.getStatus().name());
        boolean terminal = status == DailyGameViewStatus.COMPLETED || status == DailyGameViewStatus.FAILED;
        List<DailyGameMediaRefDTO> refs = media(userId, challenge, used, terminal);
        List<DailyGameAttemptViewDTO> attempts = details.stream().map(detail -> attempt(challenge, detail)).toList();
        DailyGameAnswerPreviewDTO answer = terminal ? answer(userId, challenge, refs) : null;
        LocalDate date = challenge.getChallengeDate();
        return assembler.assemble(new DailyGamePlayProjection(date,
                date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), challenge.getGameType(),
                challenge.getTargetKind(), challenge.getGameType().maxAttempts(), used, status,
                result == null ? 0 : result.getScore(), refs, attempts, answer,
                result != null && result.isShareOnCompletion(), result != null && result.getSharedAt() != null));
    }

    private List<DailyGameMediaRefDTO> media(UUID userId, DailyChallenge challenge, int used, boolean terminal) {
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
        return new DailyGameAttemptViewDTO(detail.attemptNumber(), view,
                new DailyGameAttemptOutcomeDTO(correct,
                        feedback == null ? null : feedback.seriesCorrect(),
                        feedback == null ? null : feedback.seasonCorrect(),
                        feedback == null ? null : feedback.episodeCorrect()));
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

    private void requireVisual(DailyGameType gameType) {
        if (gameType != DailyGameType.MOVIE_BY_POSTER && gameType != DailyGameType.SERIES_BY_POSTER
                && gameType != DailyGameType.PERSON_BY_FACE && gameType != DailyGameType.EPISODE_BY_FRAME) {
            throw new BadRequestException("Unsupported visual daily game type");
        }
    }
}
