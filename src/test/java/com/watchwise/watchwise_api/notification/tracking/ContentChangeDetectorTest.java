package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.common.tmdb.TmdbMovieDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbNextEpisode;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContentChangeDetectorTest {

    private final ContentChangeDetector detector = new ContentChangeDetector();
    private final LocalDate today = LocalDate.of(2026, 8, 29);

    @Test
    @DisplayName("[detectMovieChange] Should Return ANNOUNCED_DATE - When A Future Release Date First Appears")
    void shouldReturnAnnouncedDateWhenAFutureReleaseDateFirstAppears() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownReleaseDate(null).lastKnownStatus("Planned").build();
        TmdbMovieDetails fresh = new TmdbMovieDetails("603", "2026-12-01", "Planned");

        Optional<ContentChangeEvent> result = detector.detectMovieChange(previous, fresh, today);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(NotificationType.ANNOUNCED_DATE);
        assertThat(result.get().relevantDate()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    @DisplayName("[detectMovieChange] Should Return RELEASE - When Status Transitions From Post Production To Released")
    void shouldReturnReleaseWhenStatusTransitionsFromPostProductionToReleased() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownReleaseDate(LocalDate.of(2026, 8, 20)).lastKnownStatus("Post Production").build();
        TmdbMovieDetails fresh = new TmdbMovieDetails("603", "2026-08-20", "Released");

        Optional<ContentChangeEvent> result = detector.detectMovieChange(previous, fresh, today);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(NotificationType.RELEASE);
    }

    @Test
    @DisplayName("[detectMovieChange] Should Return Empty - When Status Was Already Released And Stays Released")
    void shouldReturnEmptyWhenStatusWasAlreadyReleasedAndStaysReleased() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownReleaseDate(LocalDate.of(2026, 1, 1)).lastKnownStatus("Released").build();
        TmdbMovieDetails fresh = new TmdbMovieDetails("603", "2026-01-01", "Released");

        Optional<ContentChangeEvent> result = detector.detectMovieChange(previous, fresh, today);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[detectMovieChange] Should Return CANCELLED - When Status Changes To Canceled")
    void shouldReturnCancelledWhenStatusChangesToCanceled() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownReleaseDate(null).lastKnownStatus("In Production").build();
        TmdbMovieDetails fresh = new TmdbMovieDetails("603", "", "Canceled");

        Optional<ContentChangeEvent> result = detector.detectMovieChange(previous, fresh, today);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(NotificationType.CANCELLED);
    }

    @Test
    @DisplayName("[detectMovieChange] Should Return Empty - When Nothing Changed")
    void shouldReturnEmptyWhenNothingChanged() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownReleaseDate(LocalDate.of(2026, 12, 1)).lastKnownStatus("Planned").build();
        TmdbMovieDetails fresh = new TmdbMovieDetails("603", "2026-12-01", "Planned");

        Optional<ContentChangeEvent> result = detector.detectMovieChange(previous, fresh, today);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[detectMovieChange] Should Return Empty - When First Check Has No Release Date Yet")
    void shouldReturnEmptyWhenFirstCheckHasNoReleaseDateYet() {
        TmdbMovieDetails fresh = new TmdbMovieDetails("603", "", "Planned");

        Optional<ContentChangeEvent> result = detector.detectMovieChange(null, fresh, today);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[detectMovieChange] Should Return Empty - When First Observation Already Shows Cancelled")
    void shouldReturnEmptyWhenFirstObservationAlreadyShowsCancelled() {
        TmdbMovieDetails fresh = new TmdbMovieDetails("603", "", "Canceled");

        Optional<ContentChangeEvent> result = detector.detectMovieChange(null, fresh, today);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[detectTvChange] Should Return RENEWED - When Status Moves From Ended To Returning Series")
    void shouldReturnRenewedWhenStatusMovesFromEndedToReturningSeries() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownStatus("Ended").build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Returning Series", null, null);

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).extracting(ContentChangeEvent::type).containsExactly(NotificationType.RENEWED);
    }

    @Test
    @DisplayName("[detectTvChange] Should Return CANCELLED - When Status Changes To Canceled")
    void shouldReturnCancelledWhenStatusChangesToCanceledForTvShow() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownStatus("Returning Series").build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Canceled", null, null);

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).extracting(ContentChangeEvent::type).containsExactly(NotificationType.CANCELLED);
    }

    @Test
    @DisplayName("[detectTvChange] Should Return NEW_EPISODE - When The Known Next Episode Air Date Has Passed")
    void shouldReturnNewEpisodeWhenTheKnownNextEpisodeAirDateHasPassed() {
        TrackedContentState previous = TrackedContentState.builder()
                .lastKnownStatus("Returning Series")
                .nextEpisodeAirDate(LocalDate.of(2026, 8, 25))
                .nextEpisodeSeasonNumber(6).nextEpisodeNumber(3)
                .build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Returning Series",
                new TmdbNextEpisode("2026-09-05", 6, 4), null);

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).extracting(ContentChangeEvent::type).containsExactly(NotificationType.NEW_EPISODE);
        assertThat(result.getFirst().seasonNumber()).isEqualTo(6);
        assertThat(result.getFirst().episodeNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("[detectTvChange] Should Return Both RENEWED And NEW_EPISODE - When Both Conditions Are True In The Same Cycle")
    void shouldReturnBothRenewedAndNewEpisodeWhenBothConditionsAreTrueInTheSameCycle() {
        TrackedContentState previous = TrackedContentState.builder()
                .lastKnownStatus("Ended")
                .nextEpisodeAirDate(LocalDate.of(2026, 8, 25))
                .nextEpisodeSeasonNumber(6).nextEpisodeNumber(3)
                .build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Returning Series",
                new TmdbNextEpisode("2026-09-05", 6, 4), null);

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).extracting(ContentChangeEvent::type)
                .containsExactlyInAnyOrder(NotificationType.RENEWED, NotificationType.NEW_EPISODE);
    }

    @Test
    @DisplayName("[detectTvChange] Should Return Empty List - When First Observation Already Shows Cancelled")
    void shouldReturnEmptyListWhenFirstObservationAlreadyShowsCancelled() {
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Canceled", null, null);

        List<ContentChangeEvent> result = detector.detectTvChange(null, fresh, today);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[detectTvChange] Should Return RELEASE - When Status Transitions From Planned To Returning Series")
    void shouldReturnReleaseWhenStatusTransitionsFromPlannedToReturningSeries() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownStatus("Planned").build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Returning Series", null, null);

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).extracting(ContentChangeEvent::type).containsExactly(NotificationType.RELEASE);
    }

    @Test
    @DisplayName("[detectTvChange] Should Return Empty List - When Status Transitions Between Pre-Release Stages")
    void shouldReturnEmptyListWhenStatusTransitionsBetweenPreReleaseStages() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownStatus("Planned").build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "In Production", null, null);

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[detectTvChange] Should Return ANNOUNCED_DATE With Season Number - When A New Season's Air Date First Appears")
    void shouldReturnAnnouncedDateWithSeasonNumberWhenANewSeasonsAirDateFirstAppears() {
        TrackedContentState previous = TrackedContentState.builder()
                .lastKnownStatus("Returning Series")
                .lastKnownSeasonNumber(2).lastKnownSeasonAirDate(LocalDate.of(2025, 1, 1))
                .build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Returning Series", null,
                List.of(new TmdbSeasonSummary(1, "Season 1", "", "2024-01-01", 10, null),
                        new TmdbSeasonSummary(2, "Season 2", "", "2025-01-01", 8, null),
                        new TmdbSeasonSummary(3, "Season 3", "", "2027-01-10", 0, null)));

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).extracting(ContentChangeEvent::type).containsExactly(NotificationType.ANNOUNCED_DATE);
        assertThat(result.getFirst().relevantDate()).isEqualTo(LocalDate.of(2027, 1, 10));
        assertThat(result.getFirst().seasonNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("[detectTvChange] Should Return Empty List - When The Latest Season's Air Date Is Already Known")
    void shouldReturnEmptyListWhenTheLatestSeasonsAirDateIsAlreadyKnown() {
        TrackedContentState previous = TrackedContentState.builder()
                .lastKnownStatus("Returning Series")
                .lastKnownSeasonNumber(3).lastKnownSeasonAirDate(LocalDate.of(2027, 1, 10))
                .build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Returning Series", null,
                List.of(new TmdbSeasonSummary(3, "Season 3", "", "2027-01-10", 0, null)));

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[detectTvChange] Should Return Empty List - When The New Season's Air Date Is Still Unknown")
    void shouldReturnEmptyListWhenTheNewSeasonsAirDateIsStillUnknown() {
        TrackedContentState previous = TrackedContentState.builder()
                .lastKnownStatus("Returning Series")
                .lastKnownSeasonNumber(2).lastKnownSeasonAirDate(LocalDate.of(2025, 1, 1))
                .build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Returning Series", null,
                List.of(new TmdbSeasonSummary(2, "Season 2", "", "2025-01-01", 8, null),
                        new TmdbSeasonSummary(3, "Season 3", "", null, 0, null)));

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[detectTvChange] Should Ignore Season Zero - When Computing The Latest Season")
    void shouldIgnoreSeasonZeroWhenComputingTheLatestSeason() {
        TrackedContentState previous = TrackedContentState.builder()
                .lastKnownStatus("Returning Series")
                .lastKnownSeasonNumber(1).lastKnownSeasonAirDate(LocalDate.of(2024, 1, 1))
                .build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Returning Series", null,
                List.of(new TmdbSeasonSummary(1, "Season 1", "", "2024-01-01", 10, null),
                        new TmdbSeasonSummary(0, "Specials", "", "2027-05-01", 2, null)));

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[detectTvChange] Should Return Empty List - When Nothing Changed And No Episode Air Date Passed")
    void shouldReturnEmptyListWhenNothingChangedAndNoEpisodeAirDatePassed() {
        TrackedContentState previous = TrackedContentState.builder()
                .lastKnownStatus("Returning Series")
                .nextEpisodeAirDate(LocalDate.of(2026, 9, 5))
                .nextEpisodeSeasonNumber(6).nextEpisodeNumber(4)
                .build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Returning Series",
                new TmdbNextEpisode("2026-09-05", 6, 4), null);

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).isEmpty();
    }
}
