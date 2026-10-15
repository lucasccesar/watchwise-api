package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.dailygame.dto.*;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.media.DailyGameImageVariant;
import com.watchwise.watchwise_api.dailygame.media.DailyGameMediaSlot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DailyGameInfoPlayAssemblerTest {
    private final DailyGameInfoPlayAssembler assembler = new DailyGameInfoPlayAssembler();

    @Test
    void addsTypedInfoFeedbackToAttemptWithoutDroppingAttemptFields() {
        var candidate = new DailyGameCandidateViewDTO(DailyGameTargetKind.SERIES, "candidate", null,
                null, null, null, "Candidate", "/candidate.jpg", 2024, List.of("Drama"), 3);
        var outcome = new DailyGameAttemptOutcomeDTO(false, null, null, null);
        var attempt = new DailyGameAttemptViewDTO(2, candidate, outcome);
        var feedback = infoFeedback(24);

        var result = assembler.assemble(attempt, feedback);

        assertThat(result.attemptNumber()).isEqualTo(2);
        assertThat(result.candidate()).isSameAs(candidate);
        assertThat(result.outcome()).isSameAs(outcome);
        assertThat(result.infoFeedback()).isSameAs(feedback);
        assertThat(result.infoFeedback().candidateEpisodeCount()).isEqualTo(24);
        assertThat(result.infoFeedback().platforms()).isEqualTo(feedback.platforms());
        assertThat(result.infoFeedback().genres()).isEqualTo(feedback.genres());
        assertThat(result.infoFeedback().year()).isEqualTo(feedback.year());
        assertThat(result.infoFeedback().revenueOrSeasons()).isEqualTo(feedback.revenueOrSeasons());
    }

    @Test
    void filmographyViewRecordsDefensivelyCopyAndNormalizeCollections() {
        var genres = new ArrayList<>(List.of("Drama"));
        var actorReference = new DailyGameFilmographyActorReferenceDTO(
                "person-1", "Actor One", "/actor-one.jpg");
        var sharedWith = new ArrayList<>(List.of(actorReference));
        var work = new DailyGameFilmographyWorkViewDTO("work-1", "Work", true, false, 2020,
                genres, "/poster.jpg", 8, "2020-01-01", "Detective", sharedWith);
        var actors = new ArrayList<>(List.of(new DailyGameFilmographyActorViewDTO(
                "person-1", "Actor", "/profile.jpg", 35, 1, 1)));
        var entries = new ArrayList<>(List.of(work));
        var state = new DailyGameFilmographyPlayStateDTO(true, actors, entries, 2, 4, 6);

        genres.add("Comedy");
        sharedWith.add(new DailyGameFilmographyActorReferenceDTO(
                "person-2", "Actor Two", "/actor-two.jpg"));
        actors.clear();
        entries.clear();

        assertThat(work.genres()).containsExactly("Drama");
        assertThat(work.sharedWith()).containsExactly(actorReference);
        assertThat(state.guessedActors()).hasSize(1);
        assertThat(state.entries()).containsExactly(work);
        assertThat(state.attemptsUsed()).isEqualTo(2);
        assertThat(state.maxAttempts()).isEqualTo(6);
        assertThat(new DailyGameFilmographyWorkViewDTO("work-2", null, false, false, null,
                null, null, null, null, null, null).genres()).isEmpty();
        assertThat(new DailyGameFilmographyPlayStateDTO(false, null, null, 0, 6, 6).guessedActors())
                .isEmpty();
        assertThatThrownBy(() -> work.sharedWith().add(new DailyGameFilmographyActorReferenceDTO(
                "person-3", "Actor Three", "/actor-three.jpg")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void legacyVisualConstructorsKeepExistingFieldsAndDefaultNewFieldsToNull() {
        var candidate = new DailyGameCandidateViewDTO(DailyGameTargetKind.MOVIE, "movie", null,
                null, null, null, "Movie", "/movie.jpg", 2020, List.of(), null);
        var attempt = new DailyGameAttemptViewDTO(1, candidate,
                new DailyGameAttemptOutcomeDTO(false, null, null, null));
        var play = new DailyGamePlayViewDTO(LocalDate.of(2026, 10, 15),
                Instant.parse("2026-10-16T00:00:00Z"), DailyGameType.MOVIE_BY_INFO,
                DailyGameTargetKind.MOVIE, 6, 1, 5, DailyGameViewStatus.IN_PROGRESS,
                0, 5, List.of(new DailyGameMediaRefDTO(DailyGameMediaSlot.CLUE, null,
                DailyGameImageVariant.ORIGINAL, false)), List.of(attempt), null,
                true, true, false, false);

        assertThat(attempt.attemptNumber()).isEqualTo(1);
        assertThat(attempt.infoFeedback()).isNull();
        assertThat(play.gameType()).isEqualTo(DailyGameType.MOVIE_BY_INFO);
        assertThat(play.attempts()).containsExactly(attempt);
        assertThat(play.canAttempt()).isTrue();
        assertThat(play.filmography()).isNull();
    }

    private DailyGameInfoFeedbackDTO infoFeedback(Integer candidateEpisodeCount) {
        var matched = new DailyGameComparisonCellDTO(DailyGameComparisonStatus.MATCH,
                DailyGameComparisonDirection.SECRET_HIGHER, "expected", List.of("expected"), 1);
        return new DailyGameInfoFeedbackDTO(matched, matched, matched, matched, matched, matched,
                matched, matched, candidateEpisodeCount);
    }
}
