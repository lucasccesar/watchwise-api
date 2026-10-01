package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCredits;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbCredits;
import com.watchwise.watchwise_api.common.tmdb.TmdbGenre;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvContentRating;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvContentRatings;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.generation.DailyChallengeSnapshotAssembler;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeRepository;
import com.watchwise.watchwise_api.dailygame.service.DailyGameFilmographyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DailyChallengeSnapshotRepairServiceImplTest {

    private static final String LANGUAGE = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;
    private static final LocalDate CHALLENGE_DATE = LocalDate.of(2026, 9, 30);
    private static final String IMAGE_PATH = "/legacy-image.jpg";

    @Mock
    private DailyChallengeRepository challengeRepository;

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private DailyGameFilmographyService filmographyService;

    private DailyChallengeSnapshotAssembler snapshotAssembler;
    private DailyChallengeSnapshotRepairServiceImpl repairService;

    @BeforeEach
    void setUp() {
        snapshotAssembler = new DailyChallengeSnapshotAssembler();
        repairService = new DailyChallengeSnapshotRepairServiceImpl(
                challengeRepository, tmdbClient, snapshotAssembler, filmographyService);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Add Canonical Movie Information - When The Snapshot Has Only Base Fields")
    void shouldAddCanonicalMovieInformationWhenTheSnapshotHasOnlyBaseFields() {
        Map<String, Object> legacySnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "MOVIE",
                "tmdbId", "550",
                "title", "Fight Club",
                "imageUrl", IMAGE_PATH,
                "sourceTmdbId", "550"));
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550", "550", legacySnapshot,
                "MOVIE:550");
        when(tmdbClient.getMovieFullDetails("550", LANGUAGE)).thenReturn(found(movieDetails("550")));
        when(tmdbClient.getMovieReleaseDates("550", LANGUAGE)).thenReturn(found(movieReleaseDates("550")));

        assertThat(repairService.repairIfIncomplete(challenge)).isTrue();

        assertThat(challenge.getAnswerSnapshot())
                .containsEntry("year", 2000)
                .containsEntry("genres", List.of("Drama"))
                .doesNotContainKey("hints");
        assertThat(challenge.getAnswerKey()).isEqualTo("MOVIE:550");
        assertThat(challenge.getImagePath()).isEqualTo(IMAGE_PATH);
        assertThat(challenge.getChallengeDate()).isEqualTo(CHALLENGE_DATE);
        verify(challengeRepository).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Add Canonical Series Information - When The Snapshot Has Only Base Fields")
    void shouldAddCanonicalSeriesInformationWhenTheSnapshotHasOnlyBaseFields() {
        Map<String, Object> legacySnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "SERIES",
                "tmdbId", "1396",
                "title", "Breaking Bad",
                "imageUrl", IMAGE_PATH,
                "sourceTmdbId", "1396"));
        DailyChallenge challenge = challenge(DailyGameType.SERIES_BY_INFO, "1396", "1396", legacySnapshot,
                "SERIES:1396");
        when(tmdbClient.getTvFullDetails("1396", LANGUAGE)).thenReturn(found(seriesDetails("1396")));
        when(tmdbClient.getTvContentRatings("1396", LANGUAGE)).thenReturn(found(tvContentRatings("1396")));

        assertThat(repairService.repairIfIncomplete(challenge)).isTrue();

        assertThat(challenge.getAnswerSnapshot())
                .containsEntry("year", 2000)
                .containsEntry("genres", List.of("Drama"))
                .doesNotContainKey("hints");
        assertThat(challenge.getAnswerKey()).isEqualTo("SERIES:1396");
        assertThat(challenge.getImagePath()).isEqualTo(IMAGE_PATH);
        assertThat(challenge.getChallengeDate()).isEqualTo(CHALLENGE_DATE);
        verify(challengeRepository).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Preserve Existing Information - When The Canonical Value Is Already Present")
    void shouldPreserveExistingInformationWhenTheCanonicalValueIsAlreadyPresent() {
        Map<String, Object> legacySnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "MOVIE",
                "tmdbId", "550",
                "title", "Fight Club",
                "imageUrl", IMAGE_PATH,
                "sourceTmdbId", "550",
                "genres", List.of("Legacy genre")));
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550", "550", legacySnapshot,
                "MOVIE:550");
        when(tmdbClient.getMovieFullDetails("550", LANGUAGE)).thenReturn(found(movieDetails("550")));
        when(tmdbClient.getMovieReleaseDates("550", LANGUAGE)).thenReturn(found(movieReleaseDates("550")));

        assertThat(repairService.repairIfIncomplete(challenge)).isTrue();

        assertThat(challenge.getAnswerSnapshot()).containsEntry("genres", List.of("Legacy genre"));
        verify(challengeRepository).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Preserve Existing Title - When TMDB Title Changes")
    void shouldPreserveExistingTitleWhenTmdbTitleChanges() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "MOVIE", "tmdbId", "550", "title", "Frozen legacy title",
                "imageUrl", IMAGE_PATH, "sourceTmdbId", "550"));
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550", "550", originalSnapshot,
                "MOVIE:550");
        when(tmdbClient.getMovieFullDetails("550", LANGUAGE))
                .thenReturn(found(movieDetails("550", "/poster.jpg", "Current TMDB title")));
        when(tmdbClient.getMovieReleaseDates("550", LANGUAGE)).thenReturn(found(movieReleaseDates("550")));

        assertThat(repairService.repairIfIncomplete(challenge)).isTrue();

        assertThat(challenge.getAnswerSnapshot())
                .containsEntry("title", "Frozen legacy title")
                .containsEntry("sourceTmdbId", "550");
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Add Movie Filmography And Be Idempotent - When The Legacy Snapshot Omits It")
    void shouldAddMovieFilmographyAndBeIdempotentWhenTheLegacySnapshotOmitsIt() {
        DailyChallenge challenge = actorChallenge(
                DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, "287", "550", "PERSON:287");
        List<Map<String, Object>> entries = List.of(Map.of(
                "workId", "680", "title", "Pulp Fiction", "mediaType", "movie"));
        when(filmographyService.snapshot("287", DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY))
                .thenReturn(new DailyGameFilmographyService.FilmographySnapshot(entries));

        assertThat(repairService.repairIfIncomplete(challenge)).isTrue();
        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).containsEntry("filmography", entries);
        verify(filmographyService, times(1))
                .snapshot("287", DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY);
        verify(challengeRepository, times(1)).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Add Series Filmography And Be Idempotent - When The Legacy Snapshot Omits It")
    void shouldAddSeriesFilmographyAndBeIdempotentWhenTheLegacySnapshotOmitsIt() {
        DailyChallenge challenge = actorChallenge(
                DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY, "17419", "1396", "PERSON:17419");
        List<Map<String, Object>> entries = List.of(Map.of(
                "workId", "1396", "title", "Breaking Bad", "mediaType", "tv", "episodeCount", 5));
        when(filmographyService.snapshot("17419", DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY))
                .thenReturn(new DailyGameFilmographyService.FilmographySnapshot(entries));

        assertThat(repairService.repairIfIncomplete(challenge)).isTrue();
        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).containsEntry("filmography", entries);
        verify(filmographyService, times(1))
                .snapshot("17419", DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY);
        verify(challengeRepository, times(1)).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Leave Movie Snapshot Untouched - When Full Details Are Not Found")
    void shouldLeaveMovieSnapshotUntouchedWhenFullDetailsAreNotFound() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "MOVIE", "tmdbId", "550", "title", "Fight Club"));
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550", "550", originalSnapshot,
                "MOVIE:550");
        when(tmdbClient.getMovieFullDetails("550", LANGUAGE)).thenReturn(new TmdbLookupResult.NotFound<>());

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Leave Series Snapshot Untouched - When Full Details Are Unavailable")
    void shouldLeaveSeriesSnapshotUntouchedWhenFullDetailsAreUnavailable() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "SERIES", "tmdbId", "1396", "title", "Breaking Bad"));
        DailyChallenge challenge = challenge(DailyGameType.SERIES_BY_INFO, "1396", "1396", originalSnapshot,
                "SERIES:1396");
        when(tmdbClient.getTvFullDetails("1396", LANGUAGE)).thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Reject Incomplete Found Movie Metadata - Without Saving")
    void shouldRejectIncompleteFoundMovieMetadataWithoutSaving() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "MOVIE", "tmdbId", "550", "title", "Fight Club"));
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550", "550", originalSnapshot,
                "MOVIE:550");
        when(tmdbClient.getMovieFullDetails("550", LANGUAGE))
                .thenReturn(found(incompleteMovieDetails()));
        lenient().when(tmdbClient.getMovieReleaseDates("550", LANGUAGE))
                .thenReturn(found(movieReleaseDates("550")));

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        verify(tmdbClient, never()).getMovieReleaseDates("550", LANGUAGE);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Reject Incomplete Found Series Metadata - Without Saving")
    void shouldRejectIncompleteFoundSeriesMetadataWithoutSaving() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "SERIES", "tmdbId", "1396", "title", "Breaking Bad"));
        DailyChallenge challenge = challenge(DailyGameType.SERIES_BY_INFO, "1396", "1396", originalSnapshot,
                "SERIES:1396");
        when(tmdbClient.getTvFullDetails("1396", LANGUAGE))
                .thenReturn(found(incompleteSeriesDetails()));
        lenient().when(tmdbClient.getTvContentRatings("1396", LANGUAGE))
                .thenReturn(found(tvContentRatings("1396")));

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        verify(tmdbClient, never()).getTvContentRatings("1396", LANGUAGE);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Skip TMDB - When The Movie Snapshot Is Complete")
    void shouldSkipTmdbWhenTheMovieSnapshotIsComplete() {
        Map<String, Object> completeSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "MOVIE", "tmdbId", "550", "title", "Fight Club",
                "imageUrl", IMAGE_PATH, "year", 2000, "genres", List.of("Drama")));
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550", "550", completeSnapshot,
                "MOVIE:550");

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        verifyNoInteractions(tmdbClient);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Skip TMDB - When The Series Snapshot Is Complete")
    void shouldSkipTmdbWhenTheSeriesSnapshotIsComplete() {
        Map<String, Object> completeSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "SERIES", "tmdbId", "1396", "title", "Breaking Bad",
                "imageUrl", IMAGE_PATH, "year", 2008, "genres", List.of("Drama")));
        DailyChallenge challenge = challenge(DailyGameType.SERIES_BY_INFO, "1396", "1396", completeSnapshot,
                "SERIES:1396");

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        verifyNoInteractions(tmdbClient);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Reject Partial Canonical Movie Snapshot - Without Saving")
    void shouldRejectPartialCanonicalMovieSnapshotWithoutSaving() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "MOVIE", "tmdbId", "550", "title", "Fight Club"));
        Map<String, Object> originalDisplaySnapshot = new LinkedHashMap<>(Map.of("imageUrl", IMAGE_PATH));
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550", "550", originalSnapshot,
                "MOVIE:550").toBuilder().displaySnapshot(originalDisplaySnapshot).build();
        when(tmdbClient.getMovieFullDetails("550", LANGUAGE)).thenReturn(found(movieDetailsWithoutComparableData()));
        when(tmdbClient.getMovieReleaseDates("550", LANGUAGE))
                .thenReturn(found(new TmdbMovieReleaseDates("550", List.of())));

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        assertThat(challenge.getTargetTmdbId()).isEqualTo("550");
        assertThat(challenge.getSourceTmdbId()).isEqualTo("550");
        assertThat(challenge.getAnswerKey()).isEqualTo("MOVIE:550");
        assertThat(challenge.getImagePath()).isEqualTo(IMAGE_PATH);
        assertThat(challenge.getChallengeDate()).isEqualTo(CHALLENGE_DATE);
        assertThat(challenge.getDisplaySnapshot()).isSameAs(originalDisplaySnapshot);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Reject Mismatched Movie Identity - Without Saving")
    void shouldRejectMismatchedMovieIdentityWithoutSaving() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "MOVIE", "tmdbId", "550", "title", "Fight Club"));
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550", "550", originalSnapshot,
                "MOVIE:550");
        when(tmdbClient.getMovieFullDetails("550", LANGUAGE)).thenReturn(found(movieDetails("999")));

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        assertThat(challenge.getAnswerKey()).isEqualTo("MOVIE:550");
        assertThat(challenge.getImagePath()).isEqualTo(IMAGE_PATH);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Reject Mismatched Series Identity - Without Saving")
    void shouldRejectMismatchedSeriesIdentityWithoutSaving() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "SERIES", "tmdbId", "1396", "title", "Breaking Bad"));
        DailyChallenge challenge = challenge(DailyGameType.SERIES_BY_INFO, "1396", "1396", originalSnapshot,
                "SERIES:1396");
        when(tmdbClient.getTvFullDetails("1396", LANGUAGE)).thenReturn(found(seriesDetails("999")));

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        assertThat(challenge.getAnswerKey()).isEqualTo("SERIES:1396");
        assertThat(challenge.getImagePath()).isEqualTo(IMAGE_PATH);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Reject Mismatched Target Kind - Without Saving")
    void shouldRejectMismatchedTargetKindWithoutSaving() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "SERIES", "tmdbId", "550", "title", "Fight Club"));
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550", "550", originalSnapshot,
                "MOVIE:550");

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        verifyNoInteractions(tmdbClient, filmographyService);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Reject Mismatched Movie ID - Without Saving")
    void shouldRejectMismatchedMovieIdWithoutSaving() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "MOVIE", "tmdbId", "999", "title", "Fight Club"));
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550", "550", originalSnapshot,
                "MOVIE:550");

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        verifyNoInteractions(tmdbClient, filmographyService);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Reject Mismatched Person ID - Without Saving")
    void shouldRejectMismatchedPersonIdWithoutSaving() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "PERSON", "personTmdbId", "999", "title", "Brad Pitt"));
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, "287", "550",
                originalSnapshot, "PERSON:287");

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        verifyNoInteractions(tmdbClient, filmographyService);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Reject Mismatched Source ID - Without Saving")
    void shouldRejectMismatchedSourceIdWithoutSaving() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "PERSON", "personTmdbId", "287", "sourceTmdbId", "999",
                "title", "Brad Pitt"));
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, "287", "550",
                originalSnapshot, "PERSON:287");

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        verifyNoInteractions(tmdbClient, filmographyService);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Reject Blank Movie Title - Without Saving")
    void shouldRejectBlankMovieTitleWithoutSaving() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "MOVIE", "tmdbId", "550", "title", "Fight Club"));
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550", "550", originalSnapshot,
                "MOVIE:550");
        when(tmdbClient.getMovieFullDetails("550", LANGUAGE))
                .thenReturn(found(movieDetails("550", "/poster.jpg", " ")));

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Reject Blank Series Name - Without Saving")
    void shouldRejectBlankSeriesNameWithoutSaving() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "SERIES", "tmdbId", "1396", "title", "Breaking Bad"));
        DailyChallenge challenge = challenge(DailyGameType.SERIES_BY_INFO, "1396", "1396", originalSnapshot,
                "SERIES:1396");
        when(tmdbClient.getTvFullDetails("1396", LANGUAGE))
                .thenReturn(found(seriesDetails("1396", "/poster.jpg", " ")));

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Preserve Stored Image - When The Remote Movie Poster Is Missing")
    void shouldPreserveStoredImageWhenTheRemoteMoviePosterIsMissing() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "MOVIE", "tmdbId", "550", "title", "Fight Club"));
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550", "550", originalSnapshot,
                "MOVIE:550");
        when(tmdbClient.getMovieFullDetails("550", LANGUAGE)).thenReturn(found(movieDetails("550", null)));
        when(tmdbClient.getMovieReleaseDates("550", LANGUAGE)).thenReturn(found(movieReleaseDates("550")));

        assertThat(repairService.repairIfIncomplete(challenge)).isTrue();

        assertThat(challenge.getImagePath()).isEqualTo(IMAGE_PATH);
        assertThat(challenge.getAnswerSnapshot()).containsEntry("imageUrl", IMAGE_PATH);
        verify(challengeRepository).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Replace Malformed Filmography Map")
    void shouldReplaceMalformedFilmographyMap() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "PERSON", "personTmdbId", "287", "title", "Brad Pitt",
                "filmography", Map.of("unexpected", "shape")));
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, "287", "550",
                originalSnapshot, "PERSON:287");
        List<Map<String, Object>> entries = List.of(Map.of(
                "workId", "680", "title", "Pulp Fiction", "mediaType", "movie"));
        when(filmographyService.snapshot("287", DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY))
                .thenReturn(new DailyGameFilmographyService.FilmographySnapshot(entries));

        assertThat(repairService.repairIfIncomplete(challenge)).isTrue();

        assertThat(challenge.getAnswerSnapshot()).containsEntry("filmography", entries);
        verify(challengeRepository).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Replace Malformed Filmography Entry")
    void shouldReplaceMalformedFilmographyEntry() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "PERSON", "personTmdbId", "287", "title", "Brad Pitt",
                "filmography", List.of(Map.of(
                        "workId", "680", "title", "Pulp Fiction", "mediaType", "tv"))));
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, "287", "550",
                originalSnapshot, "PERSON:287");
        List<Map<String, Object>> canonicalEntries = List.of(Map.of(
                "workId", "680", "title", "Pulp Fiction", "mediaType", "movie"));
        when(filmographyService.snapshot("287", DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY))
                .thenReturn(new DailyGameFilmographyService.FilmographySnapshot(canonicalEntries));

        assertThat(repairService.repairIfIncomplete(challenge)).isTrue();

        assertThat(challenge.getAnswerSnapshot()).containsEntry("filmography", canonicalEntries);
        verify(challengeRepository).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Replace Series Filmography Entry Without Positive Episode Count")
    void shouldReplaceSeriesFilmographyEntryWithoutPositiveEpisodeCount() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "PERSON", "personTmdbId", "17419", "title", "Actor",
                "filmography", List.of(Map.of(
                        "workId", "1396", "title", "Breaking Bad", "mediaType", "tv", "episodeCount", 0))));
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY, "17419", "1396",
                originalSnapshot, "PERSON:17419");
        List<Map<String, Object>> canonicalEntries = List.of(Map.of(
                "workId", "1396", "title", "Breaking Bad", "mediaType", "tv", "episodeCount", 5));
        when(filmographyService.snapshot("17419", DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY))
                .thenReturn(new DailyGameFilmographyService.FilmographySnapshot(canonicalEntries));

        assertThat(repairService.repairIfIncomplete(challenge)).isTrue();

        assertThat(challenge.getAnswerSnapshot()).containsEntry("filmography", canonicalEntries);
        verify(challengeRepository).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Replace Malformed Filmography Scalar")
    void shouldReplaceMalformedFilmographyScalar() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "PERSON", "personTmdbId", "287", "title", "Brad Pitt",
                "filmography", "unexpected shape"));
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, "287", "550",
                originalSnapshot, "PERSON:287");
        List<Map<String, Object>> entries = List.of(Map.of(
                "workId", "680", "title", "Pulp Fiction", "mediaType", "movie"));
        when(filmographyService.snapshot("287", DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY))
                .thenReturn(new DailyGameFilmographyService.FilmographySnapshot(entries));

        assertThat(repairService.repairIfIncomplete(challenge)).isTrue();

        assertThat(challenge.getAnswerSnapshot()).containsEntry("filmography", entries);
        verify(challengeRepository).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Leave Movie Snapshot Untouched - When Certification Is Unavailable")
    void shouldLeaveMovieSnapshotUntouchedWhenCertificationIsUnavailable() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "MOVIE", "tmdbId", "550", "title", "Fight Club"));
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550", "550", originalSnapshot,
                "MOVIE:550");
        when(tmdbClient.getMovieFullDetails("550", LANGUAGE)).thenReturn(found(movieDetails("550")));
        when(tmdbClient.getMovieReleaseDates("550", LANGUAGE))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    @Test
    @DisplayName("[repairIfIncomplete] Should Leave Filmography Snapshot Untouched - When The Filmography Is Empty")
    void shouldLeaveFilmographySnapshotUntouchedWhenTheFilmographyIsEmpty() {
        Map<String, Object> originalSnapshot = new LinkedHashMap<>(Map.of(
                "targetKind", "PERSON", "personTmdbId", "287", "title", "Brad Pitt"));
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, "287", "550",
                originalSnapshot, "PERSON:287");
        when(filmographyService.snapshot("287", DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY))
                .thenReturn(new DailyGameFilmographyService.FilmographySnapshot(List.of()));

        assertThat(repairService.repairIfIncomplete(challenge)).isFalse();

        assertThat(challenge.getAnswerSnapshot()).isSameAs(originalSnapshot);
        verify(challengeRepository, never()).saveAndFlush(challenge);
    }

    private DailyChallenge actorChallenge(
            DailyGameType gameType, String personTmdbId, String sourceTmdbId, String answerKey) {
        return challenge(gameType, personTmdbId, sourceTmdbId,
                new LinkedHashMap<>(snapshotAssembler.person(gameType, personTmdbId, "Actor", IMAGE_PATH,
                        sourceTmdbId, List.of()).answerSnapshot()), answerKey);
    }

    private DailyChallenge challenge(
            DailyGameType gameType, String targetTmdbId, String sourceTmdbId,
            Map<String, Object> answerSnapshot, String answerKey) {
        return DailyChallenge.builder()
                .challengeDate(CHALLENGE_DATE)
                .gameType(gameType)
                .targetKind(gameType.targetKind())
                .targetTmdbId(targetTmdbId)
                .answerKey(answerKey)
                .sourceTmdbId(sourceTmdbId)
                .imagePath(IMAGE_PATH)
                .answerSnapshot(answerSnapshot)
                .displaySnapshot(Map.of("imageUrl", IMAGE_PATH))
                .createdAt(LocalDateTime.of(2026, 9, 30, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 30, 10, 0))
                .build();
    }

    private static TmdbMovieFullDetails movieDetails(String id) {
        return movieDetails(id, "/poster.jpg");
    }

    private static TmdbMovieFullDetails movieDetails(String id, String posterPath) {
        return movieDetails(id, posterPath, "Fight Club");
    }

    private static TmdbMovieFullDetails movieDetails(String id, String posterPath, String title) {
        return new TmdbMovieFullDetails(id, title, title, null, posterPath, null,
                "2000-01-01", null, List.of(new TmdbGenre(18, "Drama")), List.of(),
                new TmdbCredits(List.of(), List.of()), null, null, null, null, List.of(), null);
    }

    private static TmdbMovieFullDetails movieDetailsWithoutComparableData() {
        return new TmdbMovieFullDetails("550", "Fight Club", "Fight Club", null, "/poster.jpg", null,
                "2000-01-01", null, List.of(), List.of(), new TmdbCredits(List.of(), List.of()),
                null, null, null, null, List.of(), null);
    }

    private static TmdbMovieFullDetails incompleteMovieDetails() {
        return new TmdbMovieFullDetails("invalid-id", " ", " ", null, " ", null,
                "2000-01-01", null, List.of(new TmdbGenre(18, "Drama")), List.of(),
                new TmdbCredits(List.of(), List.of()), null, null, null, null, List.of(), null);
    }

    private static TmdbMovieReleaseDates movieReleaseDates(String id) {
        return new TmdbMovieReleaseDates(id, List.of(new TmdbRegionReleaseDates("BR", List.of(
                new TmdbMovieReleaseDate("12", "pt", "2000-01-01", null, 3)))));
    }

    private static TmdbTvFullDetails seriesDetails(String id) {
        return seriesDetails(id, "/poster.jpg", "Breaking Bad");
    }

    private static TmdbTvFullDetails seriesDetails(String id, String posterPath, String name) {
        return new TmdbTvFullDetails(id, name, name, null, posterPath, null,
                "2000-01-01", List.of(), List.of(new TmdbGenre(18, "Drama")), List.of(), List.of(),
                List.of(), null, new TmdbAggregateCredits(List.of(), List.of()), null, null, 5, 62,
                List.of(), null, "Ended");
    }

    private static TmdbTvFullDetails incompleteSeriesDetails() {
        return new TmdbTvFullDetails("invalid-id", " ", " ", null, " ", null,
                "2000-01-01", List.of(), List.of(new TmdbGenre(18, "Drama")), List.of(), List.of(),
                List.of(), null, new TmdbAggregateCredits(List.of(), List.of()), null, null, 5, 62,
                List.of(), null, "Ended");
    }

    private static TmdbTvContentRatings tvContentRatings(String id) {
        return new TmdbTvContentRatings(id, List.of(new TmdbTvContentRating("BR", "14")));
    }

    private static <T> TmdbLookupResult<T> found(T value) {
        return new TmdbLookupResult.Found<>(value);
    }
}
