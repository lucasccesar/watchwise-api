package com.watchwise.watchwise_api.diarycompletion.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.diarycompletion.model.CompletedPass;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeWatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionCalculatorTest {

    private final CompletionCalculator calculator = new CompletionCalculator();

    @Test
    @DisplayName("[calculate] Should Keep Only First Pass - When New Season Was Watched Once")
    void shouldKeepOnlyFirstPassWhenNewSeasonWasWatchedOnce() {
        var one = new EpisodeCoordinate(1, 1);
        var two = new EpisodeCoordinate(2, 1);
        var at = Instant.parse("2026-01-01T12:00:00Z");
        var watches = List.of(
                new EpisodeWatch(UUID.randomUUID(), one, 1, null, at, null),
                new EpisodeWatch(UUID.randomUUID(), one, 2, null, at.plusSeconds(1), null),
                new EpisodeWatch(UUID.randomUUID(), two, 1, null, at.plusSeconds(2), null));
        var scope = new CompletionScope(MediaReference.series("123"), Set.of(one, two), true);

        assertThat(calculator.calculate(scope, watches))
                .extracting(CompletedPass::watchNumber).containsExactly(1);
    }

    @Test
    @DisplayName("[calculate] Should Require Every Actual Coordinate In One Pass - When Episode Numbers Have Gaps")
    void shouldRequireEveryActualCoordinateInOnePassWhenEpisodeNumbersHaveGaps() {
        var one = new EpisodeCoordinate(1, 1);
        var three = new EpisodeCoordinate(1, 3);
        var scope = new CompletionScope(MediaReference.season("123", 1), Set.of(one, three), true);
        var watches = List.of(
                watch(1, one, 1, null, Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T10:00:00Z")),
                watch(2, one, 1, null, Instant.parse("2026-01-01T11:00:00Z"), Instant.parse("2026-01-01T11:00:00Z")),
                watch(3, three, 2, null, Instant.parse("2026-01-02T10:00:00Z"), Instant.parse("2026-01-02T10:00:00Z")),
                watch(4, one, 3, null, Instant.parse("2026-01-03T10:00:00Z"), Instant.parse("2026-01-03T10:00:00Z")),
                watch(5, three, 3, null, Instant.parse("2026-01-03T11:00:00Z"), Instant.parse("2026-01-03T11:00:00Z")));

        assertThat(calculator.calculate(scope, watches))
                .extracting(CompletedPass::watchNumber).containsExactly(3);
    }

    @Test
    @DisplayName("[calculate] Should Return No Passes - When Scope Is Empty Or Still Releasing")
    void shouldReturnNoPassesWhenScopeIsEmptyOrStillReleasing() {
        var one = new EpisodeCoordinate(1, 1);
        var watch = watch(1, one, 1, null, Instant.parse("2026-01-01T10:00:00Z"), null);

        assertThat(calculator.calculate(
                new CompletionScope(MediaReference.series("123"), Set.of(), true), List.of(watch))).isEmpty();
        assertThat(calculator.calculate(
                new CompletionScope(MediaReference.series("123"), Set.of(one), false), List.of(watch))).isEmpty();
    }

    @Test
    @DisplayName("[calculate] Should Prefer Watched At - When Diary Dates Disagree")
    void shouldPreferWatchedAtWhenDiaryDatesDisagree() {
        var one = new EpisodeCoordinate(1, 1);
        var two = new EpisodeCoordinate(1, 3);
        var firstId = id(1);
        var lastId = id(2);
        var scope = new CompletionScope(MediaReference.season("123", 1), Set.of(one, two), true);
        var watches = List.of(
                new EpisodeWatch(firstId, one, 1, LocalDate.parse("2026-01-30"),
                        Instant.parse("2026-01-30T12:00:00Z"), Instant.parse("2026-01-02T12:00:00Z")),
                new EpisodeWatch(lastId, two, 1, LocalDate.parse("2026-01-01"),
                        Instant.parse("2026-01-01T12:00:00Z"), Instant.parse("2026-01-03T12:00:00Z")));

        assertThat(calculator.calculate(scope, watches)).singleElement().satisfies(pass -> {
            assertThat(pass.anchorDiaryEntryId()).isEqualTo(lastId);
            assertThat(pass.completedOn()).isEqualTo(LocalDate.parse("2026-01-03"));
            assertThat(pass.orderedAt()).isEqualTo(Instant.parse("2026-01-03T12:00:00Z"));
        });
    }

    @Test
    @DisplayName("[calculate] Should Use Watched Date - When Watched At Is Missing")
    void shouldUseWatchedDateWhenWatchedAtIsMissing() {
        var one = new EpisodeCoordinate(1, 1);
        var two = new EpisodeCoordinate(1, 3);
        var lastId = id(4);
        var scope = new CompletionScope(MediaReference.season("123", 1), Set.of(one, two), true);
        var watches = List.of(
                new EpisodeWatch(id(3), one, 1, LocalDate.parse("2026-01-01"),
                        Instant.parse("2026-01-10T12:00:00Z"), null),
                new EpisodeWatch(lastId, two, 1, LocalDate.parse("2026-01-03"),
                        Instant.parse("2026-01-03T12:00:00Z"), null));

        assertThat(calculator.calculate(scope, watches)).singleElement().satisfies(pass -> {
            assertThat(pass.anchorDiaryEntryId()).isEqualTo(lastId);
            assertThat(pass.completedOn()).isEqualTo(LocalDate.parse("2026-01-03"));
        });
    }

    @Test
    @DisplayName("[calculate] Should Use Diary Activity Only For Ordering - When Watch Dates Are Missing")
    void shouldUseDiaryActivityOnlyForOrderingWhenWatchDatesAreMissing() {
        var one = new EpisodeCoordinate(1, 1);
        var two = new EpisodeCoordinate(1, 3);
        var lastId = id(6);
        var scope = new CompletionScope(MediaReference.season("123", 1), Set.of(one, two), true);
        var watches = List.of(
                new EpisodeWatch(id(5), one, 1, null, Instant.parse("2026-01-01T10:00:00Z"), null),
                new EpisodeWatch(lastId, two, 1, null, Instant.parse("2026-01-01T11:00:00Z"), null));

        assertThat(calculator.calculate(scope, watches)).singleElement().satisfies(pass -> {
            assertThat(pass.anchorDiaryEntryId()).isEqualTo(lastId);
            assertThat(pass.completedOn()).isNull();
        });
    }

    @Test
    @DisplayName("[calculate] Should Use Diary Id As Final Tie Break - Without Inventing A Completion Date")
    void shouldUseDiaryIdAsFinalTieBreakWithoutInventingACompletionDate() {
        var one = new EpisodeCoordinate(1, 1);
        var three = new EpisodeCoordinate(1, 3);
        var lastId = id(12);
        var recordedAt = Instant.parse("2026-01-01T10:00:00Z");
        var scope = new CompletionScope(MediaReference.season("123", 1), Set.of(one, three), true);
        var watches = List.of(
                new EpisodeWatch(id(11), one, 1, null, recordedAt, null),
                new EpisodeWatch(lastId, three, 1, null, recordedAt, null));

        assertThat(calculator.calculate(scope, watches)).singleElement().satisfies(pass -> {
            assertThat(pass.anchorDiaryEntryId()).isEqualTo(lastId);
            assertThat(pass.completedOn()).isNull();
            assertThat(pass.orderedAt()).isEqualTo(recordedAt);
        });
    }

    @Test
    @DisplayName("[calculate] Should Leave Completion Date Unknown - When The Final Necessary Episode Is Undated")
    void shouldLeaveCompletionDateUnknownWhenTheFinalNecessaryEpisodeIsUndated() {
        var datedEpisode = new EpisodeCoordinate(1, 1);
        var undatedEpisode = new EpisodeCoordinate(1, 3);
        var undatedEntryId = id(14);
        var scope = new CompletionScope(
                MediaReference.season("123", 1), Set.of(datedEpisode, undatedEpisode), true);
        var watches = List.of(
                new EpisodeWatch(id(13), datedEpisode, 1, LocalDate.parse("2026-01-01"),
                        Instant.parse("2026-01-03T12:00:00Z"), null),
                new EpisodeWatch(undatedEntryId, undatedEpisode, 1, null,
                        Instant.parse("2026-01-04T12:00:00Z"), null));

        assertThat(calculator.calculate(scope, watches)).singleElement().satisfies(pass -> {
            assertThat(pass.anchorDiaryEntryId()).isEqualTo(undatedEntryId);
            assertThat(pass.completedOn()).isNull();
            assertThat(pass.orderedAt()).isEqualTo(Instant.parse("2026-01-04T12:00:00Z"));
        });
    }

    @Test
    @DisplayName("[calculate] Should Keep The First Necessary Event - When A Coordinate Has A Later Duplicate")
    void shouldKeepTheFirstNecessaryEventWhenACoordinateHasALaterDuplicate() {
        var one = new EpisodeCoordinate(1, 1);
        var three = new EpisodeCoordinate(1, 3);
        var completionId = id(9);
        var scope = new CompletionScope(MediaReference.season("123", 1), Set.of(one, three), true);
        var watches = List.of(
                watch(7, one, 1, null, Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T10:00:00Z")),
                watch(8, three, 1, null, Instant.parse("2026-01-02T10:00:00Z"), Instant.parse("2026-01-02T10:00:00Z")),
                watch(completionId, one, 1, null, Instant.parse("2026-01-10T10:00:00Z"), Instant.parse("2026-01-10T10:00:00Z")));

        assertThat(calculator.calculate(scope, watches)).singleElement().satisfies(pass -> {
            assertThat(pass.anchorDiaryEntryId()).isEqualTo(id(8));
            assertThat(pass.completedOn()).isEqualTo(LocalDate.parse("2026-01-02"));
        });
    }

    private static EpisodeWatch watch(
            int id,
            EpisodeCoordinate coordinate,
            int watchNumber,
            LocalDate watchedDate,
            Instant recordedAt,
            Instant watchedAt) {
        return watch(CompletionCalculatorTest.id(id), coordinate, watchNumber, watchedDate, recordedAt, watchedAt);
    }

    private static EpisodeWatch watch(
            UUID id,
            EpisodeCoordinate coordinate,
            int watchNumber,
            LocalDate watchedDate,
            Instant recordedAt,
            Instant watchedAt) {
        return new EpisodeWatch(id, coordinate, watchNumber, watchedDate, recordedAt, watchedAt);
    }

    private static UUID id(int value) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
    }
}
