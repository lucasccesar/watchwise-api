package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.dailygame.dto.*;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.media.DailyGameImageVariant;
import com.watchwise.watchwise_api.dailygame.media.DailyGameMediaSlot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DailyGamePlayAssemblerTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 14);
    private static final Instant RESET = Instant.parse("2026-10-15T03:00:00Z");
    private final DailyGamePlayAssembler assembler = new DailyGamePlayAssembler();

    @Test
    void assemblesMovieAtAttemptTwoWithOpenAnswerAndRemainingPotentialScore() {
        var row = attempt(2, candidate(DailyGameTargetKind.MOVIE, "movie", null, null, null),
                new DailyGameAttemptOutcomeDTO(false, null, null, null));
        var view = assembler.assemble(projection(DailyGameType.MOVIE_BY_POSTER,
                DailyGameTargetKind.MOVIE, 2, DailyGameViewStatus.IN_PROGRESS, 0,
                List.of(row), null, false));

        assertThat(view.challengeDate()).isEqualTo(DATE);
        assertThat(view.nextResetAt()).isEqualTo(RESET);
        assertThat(view.potentialScore()).isEqualTo(4);
        assertThat(view.attemptsUsed()).isEqualTo(2);
        assertThat(view.attempts()).hasSize(1);
        assertThat(view.answer()).isNull();
        assertThat(view.media()).containsExactly(new DailyGameMediaRefDTO(
                DailyGameMediaSlot.CLUE, "/clue.jpg", DailyGameImageVariant.BLUR_14, true));
        assertThat(view.canAttempt()).isTrue();
        assertThat(view.canGiveUp()).isTrue();
    }

    @Test
    void assemblesCompletedSeriesWithZeroPotentialAndAnswer() {
        var answer = answer(DailyGameTargetKind.SERIES, "series", null, null, null,
                List.of("Drama"), List.of(), List.of());
        var view = assembler.assemble(projection(DailyGameType.SERIES_BY_POSTER,
                DailyGameTargetKind.SERIES, 6, DailyGameViewStatus.COMPLETED, 4,
                List.of(attempt(4, candidate(DailyGameTargetKind.SERIES, "series", null, null, null),
                        new DailyGameAttemptOutcomeDTO(true, null, null, null))), answer, false));

        assertThat(view.potentialScore()).isZero();
        assertThat(view.answer()).isEqualTo(answer);
        assertThat(view.canAttempt()).isFalse();
        assertThat(view.canGiveUp()).isFalse();
        assertThat(view.attempts()).hasSize(1);
        assertThat(view.attempts().getFirst().outcome().seriesCorrect()).isNull();
    }

    @Test
    void assemblesPersonAnswerWithoutKnownForEntries() {
        var answer = answer(DailyGameTargetKind.PERSON, null, "person", null, null,
                List.of(), List.of(), List.of());
        var view = assembler.assemble(projection(DailyGameType.PERSON_BY_FACE,
                DailyGameTargetKind.PERSON, 6, DailyGameViewStatus.FAILED, 0, List.of(), answer, false));

        assertThat(view.answer().knownForTitles()).isEmpty();
        assertThat(view.potentialScore()).isZero();
        assertThat(view.answer()).isEqualTo(answer);
        assertThat(view.attempts()).isEmpty();
    }

    @Test
    void assemblesEpisodeAtAttemptThreeWithNullableFeedbackFields() {
        var row = attempt(3, candidate(DailyGameTargetKind.EPISODE, null, null, "series", 2),
                new DailyGameAttemptOutcomeDTO(false, true, false, null));
        var view = assembler.assemble(projection(DailyGameType.EPISODE_BY_FRAME,
                DailyGameTargetKind.EPISODE, 3, DailyGameViewStatus.IN_PROGRESS, 0,
                List.of(row), null, false));

        assertThat(view.challengeDate()).isEqualTo(DATE);
        assertThat(view.nextResetAt()).isEqualTo(RESET);
        assertThat(view.potentialScore()).isEqualTo(3);
        assertThat(view.attemptsUsed()).isEqualTo(3);
        assertThat(view.answer()).isNull();
        assertThat(view.attempts().getFirst().outcome().seriesCorrect()).isTrue();
        assertThat(view.attempts().getFirst().outcome().seasonCorrect()).isFalse();
        assertThat(view.attempts().getFirst().outcome().episodeCorrect()).isNull();
    }

    private DailyGamePlayProjection projection(DailyGameType type, DailyGameTargetKind kind, int used,
            DailyGameViewStatus status, int score, List<DailyGameAttemptViewDTO> attempts,
            DailyGameAnswerPreviewDTO answer, boolean shared) {
        return new DailyGamePlayProjection(DATE, RESET, type, kind, type.maxAttempts(), used,
                status, score, List.of(new DailyGameMediaRefDTO(DailyGameMediaSlot.CLUE, "/clue.jpg",
                DailyGameImageVariant.BLUR_14, true)), attempts, answer, false, shared);
    }

    private DailyGameAttemptViewDTO attempt(int number, DailyGameCandidateViewDTO candidate,
            DailyGameAttemptOutcomeDTO outcome) {
        return new DailyGameAttemptViewDTO(number, candidate, outcome);
    }

    private DailyGameCandidateViewDTO candidate(DailyGameTargetKind kind, String tmdbId,
            String personId, String seriesId, Integer season) {
        return new DailyGameCandidateViewDTO(kind, tmdbId, personId, seriesId, season,
                kind == DailyGameTargetKind.EPISODE ? 4 : null, "Title", "/candidate.jpg", 2020,
                List.of("Drama"), null);
    }

    private DailyGameAnswerPreviewDTO answer(DailyGameTargetKind kind, String tmdbId,
            String personId, String seriesId, Integer season, List<String> genres,
            List<String> creators, List<String> knownFor) {
        return new DailyGameAnswerPreviewDTO(kind, tmdbId, personId, seriesId, season,
                kind == DailyGameTargetKind.EPISODE ? 4 : null, "Answer", null, 2020, genres,
                null, creators, knownFor, null, null, null, null);
    }
}
