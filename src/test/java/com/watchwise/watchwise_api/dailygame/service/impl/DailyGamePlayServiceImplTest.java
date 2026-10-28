package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.DailyGamesUnavailableException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageBytes;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameMediaRefDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameCandidateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameActorGuessDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameInfoFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus;
import com.watchwise.watchwise_api.dailygame.entity.*;
import com.watchwise.watchwise_api.dailygame.media.*;
import com.watchwise.watchwise_api.dailygame.repository.*;
import com.watchwise.watchwise_api.dailygame.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailyGamePlayServiceImplTest {
    private static final UUID USER = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 10, 14);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T15:00:00Z"), ZoneOffset.UTC);

    @Mock DailyChallengeRepository challenges;
    @Mock UserDailyGameResultRepository results;
    @Mock DailyGameMediaService media;
    @Mock DailyGameServiceImpl mutations;

    @Test
    void readsEachVisualGameWithoutWritingOrLookingUpMetadata() {
        for (DailyGameType type : List.of(DailyGameType.MOVIE_BY_POSTER, DailyGameType.SERIES_BY_POSTER,
                DailyGameType.PERSON_BY_FACE, DailyGameType.EPISODE_BY_FRAME)) {
            reset(challenges, results, media);
            DailyChallenge challenge = challenge(type);
            when(challenges.findByChallengeDateAndGameType(DATE, type)).thenReturn(Optional.of(challenge));
            when(results.findByUserIdAndDailyChallengeIdIn(USER, List.of(challenge.getId()))).thenReturn(List.of());
            when(media.issue(eq(USER), eq(challenge.getId()), any(), any(), anyString()))
                    .thenAnswer(call -> new DailyGameMediaRefDTO(call.getArgument(2),
                            "/games/play/media/token", call.getArgument(3), true));

            var view = service().readToday(USER, type);

            assertThat(view.challengeDate()).isEqualTo(DATE);
            assertThat(view.nextResetAt()).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
            assertThat(view.status()).isEqualTo(DailyGameViewStatus.NOT_PLAYED);
            assertThat(view.answer()).isNull();
            assertThat(view.media()).hasSize(type == DailyGameType.EPISODE_BY_FRAME ? 6 : 1);
            assertThat(view.media().getFirst().url()).startsWith("/games/play/media/");
            verify(results, never()).save(any());
            verify(results, never()).insertIfAbsent(any(), any(), any(), any());
            verifyNoInteractions(mutations);
        }
    }

    @Test
    void rejectsMissingType() {
        assertThatThrownBy(() -> service().readToday(USER, null))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(challenges, results, media, mutations);
    }

    @Test
    void readsInfoAndFilmographyGamesWithoutOpenMedia() {
        for (DailyGameType type : List.of(DailyGameType.MOVIE_BY_INFO, DailyGameType.SERIES_BY_INFO,
                DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY)) {
            reset(challenges, results, media, mutations);
            DailyChallenge challenge = challenge(type);
            if (type == DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY
                    || type == DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY) {
                challenge = challenge.toBuilder().answerSnapshot(Map.of("filmography", List.of(Map.of(
                        "workId", "77", "title", "Hidden work", "mediaType",
                        type == DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY ? "tv" : "movie",
                        "year", 2018, "genres", List.of("Drama"), "episodeCount", 8,
                        "totalEpisodes", 8, "period", "2018-01-01")))).build();
            }
            when(challenges.findByChallengeDateAndGameType(DATE, type)).thenReturn(Optional.of(challenge));
            when(results.findByUserIdAndDailyChallengeIdIn(USER, List.of(challenge.getId())))
                    .thenReturn(List.of());

            var view = service().readToday(USER, type);

            assertThat(view.gameType()).isEqualTo(type);
            assertThat(view.media()).isEmpty();
            assertThat(view.answer()).isNull();
            if (type == DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY
                    || type == DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY) {
                assertThat(view.filmography()).isNotNull();
                assertThat(view.filmography().attemptsUsed()).isZero();
                assertThat(view.filmography().attemptsRemaining()).isEqualTo(type.maxAttempts());
                assertThat(view.filmography().entries()).hasSize(1)
                        .first().extracting(entry -> entry.title()).isNull();
            } else {
                assertThat(view.filmography()).isNull();
            }
            verifyNoInteractions(media);
        }
    }

    @Test
    void terminalInfoGameIssuesProtectedOriginalAnswerMedia() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO);
        UserDailyGameResult result = UserDailyGameResult.builder().dailyChallenge(challenge)
                .attemptsUsed(1).score(0).status(DailyGameResultStatus.FAILED).build();
        when(challenges.findByChallengeDateAndGameType(DATE, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));
        when(results.findByUserIdAndDailyChallengeIdIn(USER, List.of(challenge.getId())))
                .thenReturn(List.of(result));
        when(media.issue(USER, challenge.getId(), DailyGameMediaSlot.CLUE,
                DailyGameImageVariant.ORIGINAL, "/clue.jpg"))
                .thenReturn(new DailyGameMediaRefDTO(DailyGameMediaSlot.CLUE,
                        "/games/play/media/protected-answer", DailyGameImageVariant.ORIGINAL, true));

        var view = service().readToday(USER, challenge.getGameType());

        assertThat(view.media()).hasSize(1);
        assertThat(view.media().getFirst().variant()).isEqualTo(DailyGameImageVariant.ORIGINAL);
        assertThat(view.media().getFirst().url()).isEqualTo("/games/play/media/protected-answer");
        assertThat(view.answer()).isNotNull();
        assertThat(view.answer().image().url()).isEqualTo("/games/play/media/protected-answer");
        verify(media).issue(USER, challenge.getId(), DailyGameMediaSlot.CLUE,
                DailyGameImageVariant.ORIGINAL, "/clue.jpg");
    }

    @Test
    void terminalFilmographyIssuesProtectedOriginalAnswerMedia() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY).toBuilder()
                .answerSnapshot(Map.of("title", "Actor", "filmography", List.of(Map.of(
                        "workId", "77", "title", "Series", "mediaType", "tv", "episodeCount", 8,
                        "totalEpisodes", 8))))
                .build();
        UserDailyGameResult result = UserDailyGameResult.builder().dailyChallenge(challenge)
                .attemptsUsed(1).score(0).status(DailyGameResultStatus.FAILED).build();
        when(challenges.findByChallengeDateAndGameType(DATE, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));
        when(results.findByUserIdAndDailyChallengeIdIn(USER, List.of(challenge.getId())))
                .thenReturn(List.of(result));
        when(media.issue(USER, challenge.getId(), DailyGameMediaSlot.CLUE,
                DailyGameImageVariant.ORIGINAL, "/clue.jpg"))
                .thenReturn(new DailyGameMediaRefDTO(DailyGameMediaSlot.CLUE,
                        "/games/play/media/protected-filmography-answer", DailyGameImageVariant.ORIGINAL, true));

        var view = service().readToday(USER, challenge.getGameType());

        assertThat(view.media()).singleElement().satisfies(ref -> {
            assertThat(ref.variant()).isEqualTo(DailyGameImageVariant.ORIGINAL);
            assertThat(ref.url()).isEqualTo("/games/play/media/protected-filmography-answer")
                    .isNotEqualTo(challenge.getImagePath());
        });
        assertThat(view.answer()).isNotNull();
        assertThat(view.answer().image().url()).isEqualTo("/games/play/media/protected-filmography-answer");
        verify(media).issue(USER, challenge.getId(), DailyGameMediaSlot.CLUE,
                DailyGameImageVariant.ORIGINAL, "/clue.jpg");
    }

    @Test
    void readTodayUsesRequestedAllRolesModeForSeriesFilmography() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY).toBuilder()
                .answerSnapshot(Map.of("filmography", List.of(
                        Map.of("workId", "100", "title", "Major Series", "mediaType", "tv",
                                "year", 2020, "genres", List.of("Drama"), "episodeCount", 5,
                                "totalEpisodes", 10, "period", "2018-01-01/2022-01-01"),
                        Map.of("workId", "200", "title", "Guest Series", "mediaType", "tv",
                                "year", 2019, "genres", List.of("Drama"), "episodeCount", 2,
                                "totalEpisodes", 10, "period", "2019-01-01/2019-01-01"))))
                .build();
        DailyGameAttemptDTO previous = new DailyGameAttemptDTO(1,
                new DailyGameCandidateDTO(DailyGameTargetKind.PERSON, null, "501", null,
                        null, null, "Actor", "/actor.jpg", null), null, null,
                new DailyGameFilmographyFeedbackDTO(new DailyGameActorGuessDTO("501", "Actor"),
                        List.of("SERIES:100"), List.of("SERIES:100", "SERIES:200"), List.of()));
        UserDailyGameResult result = UserDailyGameResult.builder().dailyChallenge(challenge)
                .attemptsUsed(1).score(0).status(DailyGameResultStatus.IN_PROGRESS)
                .attemptDetails(new DailyGameAttemptDetailsCodec().append(null, previous)).build();
        when(challenges.findByChallengeDateAndGameType(DATE, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));
        when(results.findByUserIdAndDailyChallengeIdIn(USER, List.of(challenge.getId())))
                .thenReturn(List.of(result));

        var view = service().readToday(USER, challenge.getGameType(), false);

        assertThat(view.filmography().majorRoles()).isFalse();
        assertThat(view.filmography().entries()).extracting(entry -> entry.workId() + ":" + entry.title())
                .containsExactly("100:Major Series", "200:Guest Series");
        assertThat(view.filmography().entries().get(1).sharedWith()).singleElement().satisfies(actor -> {
            assertThat(actor.personTmdbId()).isEqualTo("501");
            assertThat(actor.name()).isEqualTo("Actor");
            assertThat(actor.profileUrl()).isEqualTo("/actor.jpg");
        });
        verifyNoInteractions(media, mutations);
    }

    @Test
    @DisplayName("[readToday] Should Ignore Major Roles Mode For Movie Filmography - When The Visual Game Is Read")
    void readTodayIgnoresMajorRolesModeForMovieFilmography() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY).toBuilder()
                .answerSnapshot(Map.of("filmography", List.of(Map.of(
                        "workId", "100", "title", "Movie", "mediaType", "movie", "year", 2020,
                        "genres", List.of("Drama"), "period", "2020-01-01"))))
                .build();
        when(challenges.findByChallengeDateAndGameType(DATE, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));
        when(results.findByUserIdAndDailyChallengeIdIn(USER, List.of(challenge.getId())))
                .thenReturn(List.of());

        var defaultMode = service().readToday(USER, challenge.getGameType(), true).filmography();
        var disabledMode = service().readToday(USER, challenge.getGameType(), false).filmography();

        assertThat(defaultMode.majorRoles()).isFalse();
        assertThat(disabledMode.majorRoles()).isFalse();
        assertThat(disabledMode.entries()).isEqualTo(defaultMode.entries());
        assertThat(disabledMode.entries()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.workId()).isNull();
                    assertThat(entry.entryKey()).isNotBlank();
                });
        verifyNoInteractions(media, mutations);
    }

    @Test
    void infoFeedbackSurvivesAttemptAndPlayProjection() {
        DailyChallenge challenge = challenge(DailyGameType.SERIES_BY_INFO);
        UserDailyGameResult result = UserDailyGameResult.builder().dailyChallenge(challenge)
                .attemptsUsed(1).score(9).status(DailyGameResultStatus.IN_PROGRESS).build();
        DailyGameInfoFeedbackDTO feedback = new DailyGameInfoFeedbackDTO(
                null, null, null, null, null, null, null, null, 24);
        stubPreflight(challenge);
        DailyGameAttemptDTO current = new DailyGameAttemptDTO(1,
                new DailyGameCandidateDTO(DailyGameTargetKind.SERIES, "99", null, null,
                        null, null, "Other series", "/candidate.jpg", null), null, feedback, null);
        when(mutations.mutateAttempt(eq(USER), eq(DATE), eq(challenge.getGameType()), any(), eq(true), eq(true)))
                .thenReturn(new DailyGameServiceImpl.MutationResult(challenge, result, List.of(current),
                        current, null));

        var action = service().submitToday(USER, challenge.getGameType(),
                new DailyGameAttemptRequest("99", null, null, null, null));

        assertThat(action.currentAttempt().infoFeedback().candidateEpisodeCount()).isEqualTo(24);
        assertThat(action.state().attempts()).singleElement()
                .extracting(attempt -> attempt.infoFeedback().candidateEpisodeCount()).isEqualTo(24);
        assertThat(action.state().media()).isEmpty();
        assertThat(action.state().answer()).isNull();
        verify(results, never()).findByUserIdAndDailyChallengeIdIn(any(), any());
        verifyNoInteractions(media);
    }

    @Test
    void filmographyPlayActionCarriesCommittedAttemptProgressAndRevealedRows() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY).toBuilder()
                .answerSnapshot(Map.of("filmography", List.of(Map.of(
                        "workId", "77", "title", "Shared series", "mediaType", "tv",
                        "year", 2018, "genres", List.of("Drama"), "episodeCount", 8,
                        "totalEpisodes", 8, "period", "2018-01-01", "posterUrl", "/series.jpg"))))
                .build();
        stubPreflight(challenge);
        UserDailyGameResult result = UserDailyGameResult.builder().dailyChallenge(challenge)
                .attemptsUsed(1).score(9).status(DailyGameResultStatus.IN_PROGRESS).build();
        DailyGameAttemptDTO current = new DailyGameAttemptDTO(1,
                new DailyGameCandidateDTO(DailyGameTargetKind.PERSON, null, "501", null,
                        null, null, "Actor", "/actor.jpg", LocalDate.of(1964, 6, 12)), null, null,
                new DailyGameFilmographyFeedbackDTO(new DailyGameActorGuessDTO("501", "Actor"),
                        List.of("SERIES:77"), List.of("SERIES:77"), List.of()));
        when(mutations.mutateAttempt(eq(USER), eq(DATE), eq(challenge.getGameType()), any(), eq(true), eq(true)))
                .thenReturn(new DailyGameServiceImpl.MutationResult(challenge, result, List.of(current),
                        current, null));

        var action = service().submitToday(USER, challenge.getGameType(),
                new DailyGameAttemptRequest(null, "501", null, null, null));

        assertThat(action.state().filmography()).isNotNull();
        assertThat(action.state().filmography().majorRoles()).isTrue();
        assertThat(action.state().filmography().attemptsUsed()).isEqualTo(1);
        assertThat(action.state().filmography().attemptsRemaining()).isEqualTo(9);
        assertThat(action.state().filmography().guessedActors()).hasSize(1);
        assertThat(action.state().filmography().entries()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.title()).isEqualTo("Shared series");
                    assertThat(entry.revealed()).isTrue();
                    assertThat(entry.sharedWith()).singleElement().satisfies(actor -> {
                        assertThat(actor.personTmdbId()).isEqualTo("501");
                        assertThat(actor.name()).isEqualTo("Actor");
                        assertThat(actor.profileUrl()).isEqualTo("/actor.jpg");
                    });
                });
        assertThat(action.state().media()).isEmpty();
        assertThat(action.state().answer()).isNull();
        verify(results, never()).findByUserIdAndDailyChallengeIdIn(any(), any());
        verifyNoInteractions(media);
    }

    @Test
    void filmographyPlayAttemptPassesRequestedRoleModeToMutationAndProjection() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY).toBuilder()
                .answerSnapshot(Map.of("filmography", List.of(
                        Map.of("workId", "100", "title", "Major Series", "mediaType", "tv",
                                "year", 2020, "genres", List.of("Drama"), "episodeCount", 5,
                                "totalEpisodes", 10),
                        Map.of("workId", "200", "title", "Guest Series", "mediaType", "tv",
                                "year", 2019, "genres", List.of("Drama"), "episodeCount", 2,
                                "totalEpisodes", 10))))
                .build();
        stubPreflight(challenge);
        UserDailyGameResult result = UserDailyGameResult.builder().dailyChallenge(challenge)
                .attemptsUsed(1).score(0).status(DailyGameResultStatus.IN_PROGRESS).build();
        DailyGameAttemptDTO current = new DailyGameAttemptDTO(1,
                new DailyGameCandidateDTO(DailyGameTargetKind.PERSON, null, "501", null,
                        null, null, "Actor", "/actor.jpg", null), null, null,
                new DailyGameFilmographyFeedbackDTO(new DailyGameActorGuessDTO("501", "Actor"),
                        List.of("SERIES:100"), List.of("SERIES:100", "SERIES:200"), List.of()));
        when(mutations.mutateAttempt(eq(USER), eq(DATE), eq(challenge.getGameType()), any(), eq(false), eq(true)))
                .thenReturn(new DailyGameServiceImpl.MutationResult(challenge, result, List.of(current), current, null));

        var action = service().submitToday(USER, challenge.getGameType(),
                new DailyGameAttemptRequest(null, "501", null, null, null), false);

        assertThat(action.state().filmography().majorRoles()).isFalse();
        assertThat(action.state().filmography().entries()).extracting(entry -> entry.workId() + ":" + entry.title())
                .containsExactly("100:Major Series", "200:Guest Series");
        verify(mutations).mutateAttempt(USER, DATE, challenge.getGameType(),
                new DailyGameAttemptRequest(null, "501", null, null, null), false, true);
        verify(results, never()).findByUserIdAndDailyChallengeIdIn(any(), any());
        verifyNoInteractions(media);
    }

    @Test
    void terminalEpisodeReadRevealsAnswerMetadataAndAllSixFrames() {
        DailyChallenge challenge = challenge(DailyGameType.EPISODE_BY_FRAME);
        when(challenges.findByChallengeDateAndGameType(DATE, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));
        UserDailyGameResult result = UserDailyGameResult.builder().dailyChallenge(challenge)
                .attemptsUsed(2).score(0).status(DailyGameResultStatus.FAILED).build();
        when(results.findByUserIdAndDailyChallengeIdIn(USER, List.of(challenge.getId())))
                .thenReturn(List.of(result));
        when(media.issue(eq(USER), eq(challenge.getId()), any(), any(), anyString()))
                .thenAnswer(call -> new DailyGameMediaRefDTO(call.getArgument(2),
                        "/games/play/media/token", call.getArgument(3), true));

        var view = service().readToday(USER, challenge.getGameType());

        assertThat(view.status()).isEqualTo(DailyGameViewStatus.FAILED);
        assertThat(view.answer()).isNotNull();
        assertThat(view.answer().seriesTmdbId()).isEqualTo("43");
        assertThat(view.media()).hasSize(6).allSatisfy(ref ->
                assertThat(ref.url()).startsWith("/games/play/media/"));
        assertThat(view.canAttempt()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(DailyGameViewStatus.class)
    void legacyEpisodeUsesFirstSixDistinctUsableFramesWithFetchableGrants(DailyGameViewStatus status)
            throws Exception {
        DailyChallenge challenge = challenge(DailyGameType.EPISODE_BY_FRAME).toBuilder()
                .displaySnapshot(Map.of("imagePaths", Arrays.asList(null, 42, " ",
                        "https://evil.example/frame.jpg", "/frame0.jpg", "frame0.jpg", "/frame1.jpg",
                        "/frame2.jpg", "/frame3.jpg", "/frame4.jpg", "/frame5.jpg", "/frame6.jpg",
                        "/frame7.jpg"))).build();
        when(challenges.findByChallengeDateAndGameType(DATE, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));
        when(challenges.findById(challenge.getId())).thenReturn(Optional.of(challenge));
        int used = status == DailyGameViewStatus.NOT_PLAYED ? 0 : 2;
        boolean terminal = status == DailyGameViewStatus.COMPLETED || status == DailyGameViewStatus.FAILED;
        when(results.findByUserIdAndDailyChallengeIdIn(USER, List.of(challenge.getId())))
                .thenReturn(status == DailyGameViewStatus.NOT_PLAYED ? List.of() : List.of(
                        UserDailyGameResult.builder().dailyChallenge(challenge).attemptsUsed(used)
                                .status(DailyGameResultStatus.valueOf(status.name())).build()));
        TmdbClient tmdb = mock(TmdbClient.class);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", bytes);
        int visible = terminal ? 6 : used + 1;
        for (int index = 0; index < visible; index++) {
            when(tmdb.getImageBytes("/frame" + index + ".jpg")).thenReturn(new TmdbLookupResult.Found<>(
                    new TmdbImageBytes(bytes.toByteArray(), MediaType.IMAGE_PNG)));
        }
        var realMedia = new DailyGameMediaServiceImpl(tmdb, challenges, CLOCK,
                "test-daily-game-media-jwt-key-at-least-32-bytes");
        var play = new DailyGamePlayServiceImpl(challenges, results, mutations, realMedia,
                new DailyGameAttemptDetailsCodec(), new DailyGameFilmographyPlayAssembler(), CLOCK);

        var view = play.readToday(USER, challenge.getGameType());

        assertThat(view.media()).hasSize(6);
        assertThat(view.media()).extracting(DailyGameMediaRefDTO::slot).containsExactly(
                DailyGameMediaSlot.FRAME_0, DailyGameMediaSlot.FRAME_1, DailyGameMediaSlot.FRAME_2,
                DailyGameMediaSlot.FRAME_3, DailyGameMediaSlot.FRAME_4, DailyGameMediaSlot.FRAME_5);
        assertThat(view.answer() != null).isEqualTo(terminal);
        for (int index = 0; index < 6; index++) {
            var ref = view.media().get(index);
            assertThat(ref.revealed()).isEqualTo(index < visible);
            if (index < visible) {
                String token = ref.url().substring(ref.url().lastIndexOf('/') + 1);
                assertThat(realMedia.fetch(USER, token).body()).isNotEmpty();
                verify(tmdb).getImageBytes("/frame" + index + ".jpg");
            } else {
                assertThat(ref.url()).isNull();
            }
        }
        var unselected = realMedia.issue(USER, challenge.getId(), DailyGameMediaSlot.FRAME_5,
                DailyGameImageVariant.ORIGINAL, "/frame6.jpg");
        String token = unselected.url().substring(unselected.url().lastIndexOf('/') + 1);
        assertThatThrownBy(() -> realMedia.fetch(USER, token)).isInstanceOf(NotFoundException.class);
        verify(tmdb, never()).getImageBytes("/frame6.jpg");
        verify(tmdb, never()).getImageBytes("/frame7.jpg");
        verifyNoInteractions(media, mutations);
    }

    @Test
    void episodeWithFewerThanSixDistinctFramesRemainsUnavailable() {
        DailyChallenge challenge = challenge(DailyGameType.EPISODE_BY_FRAME).toBuilder()
                .displaySnapshot(Map.of("imagePaths", List.of("/a.jpg", "/b.jpg", "/c.jpg",
                        "/d.jpg", "/e.jpg", "/a.jpg", " "))).build();
        when(challenges.findByChallengeDateAndGameType(DATE, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));
        when(results.findByUserIdAndDailyChallengeIdIn(USER, List.of(challenge.getId()))).thenReturn(List.of());

        assertThatThrownBy(() -> service().readToday(USER, challenge.getGameType()))
                .isInstanceOf(DailyGamesUnavailableException.class);
        verifyNoInteractions(media, mutations);
    }

    @Test
    @DisplayName("[readToday] Should Reject Filmography Without Usable Snapshot - When Reading Visual State")
    void shouldRejectFilmographyWithoutUsableSnapshotWhenReadingVisualState() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY).toBuilder()
                .answerSnapshot(Map.of("filmography", List.of(Map.of(
                        "title", "No work identity", "mediaType", "movie"))))
                .build();
        when(challenges.findByChallengeDateAndGameType(DATE, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service().readToday(USER, challenge.getGameType()))
                .isInstanceOf(DailyGamesUnavailableException.class);

        verify(results, never()).save(any());
        verify(results, never()).insertIfAbsent(any(), any(), any(), any());
        verifyNoInteractions(media, mutations);
    }

    @Test
    @DisplayName("[readToday] Should Reveal All Works And Preserve Intersections - When Filmography Is Terminal")
    void shouldRevealAllWorksAndPreserveIntersectionsWhenFilmographyIsTerminal() {
        for (DailyGameType type : List.of(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY,
                DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY)) {
            for (DailyGameResultStatus status : List.of(DailyGameResultStatus.COMPLETED,
                    DailyGameResultStatus.FAILED)) {
                reset(challenges, results, media, mutations);
                String mediaType = type == DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY ? "tv" : "movie";
                String prefix = type == DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY ? "SERIES:" : "MOVIE:";
                DailyChallenge challenge = challenge(type).toBuilder()
                        .answerSnapshot(Map.of("title", "Secret Actor", "filmography", List.of(
                                Map.of("workId", "100", "title", "Shared work", "mediaType", mediaType,
                                        "year", 2020, "genres", List.of("Drama"), "episodeCount", 8,
                                        "totalEpisodes", 8, "posterUrl", "/100.jpg", "character", "Shared Role"),
                                Map.of("workId", "200", "title", "Unshared work", "mediaType", mediaType,
                                        "year", 2010, "genres", List.of("Drama"), "episodeCount", 8,
                                        "totalEpisodes", 8, "posterUrl", "/200.jpg", "character", "Unshared Role"))))
                        .build();
                DailyGameFilmographyFeedbackDTO feedback = new DailyGameFilmographyFeedbackDTO(
                        new DailyGameActorGuessDTO("501", "Guessed Actor"),
                        List.of(prefix + "100"), List.of(prefix + "100"), List.of());
                DailyGameAttemptDTO attempt = new DailyGameAttemptDTO(1,
                        new DailyGameCandidateDTO(DailyGameTargetKind.PERSON, null, "501", null,
                                null, null, "Guessed Actor", "/actor.jpg", null), null, null, feedback);
                UserDailyGameResult result = UserDailyGameResult.builder().dailyChallenge(challenge)
                        .attemptsUsed(1).score(status == DailyGameResultStatus.COMPLETED ? 1 : 0)
                        .status(status).attemptDetails(new DailyGameAttemptDetailsCodec().append(null, attempt)).build();
                when(challenges.findByChallengeDateAndGameType(DATE, type)).thenReturn(Optional.of(challenge));
                when(results.findByUserIdAndDailyChallengeIdIn(USER, List.of(challenge.getId())))
                        .thenReturn(List.of(result));
                when(media.issue(eq(USER), eq(challenge.getId()), any(), any(), anyString()))
                        .thenAnswer(call -> new DailyGameMediaRefDTO(call.getArgument(2),
                                "/games/play/media/terminal", call.getArgument(3), true));

                var view = service().readToday(USER, type);

                assertThat(view.status()).isEqualTo(DailyGameViewStatus.valueOf(status.name()));
                assertThat(view.filmography().entries()).hasSize(2);
                assertThat(view.filmography().entries()).filteredOn(entry -> "100".equals(entry.workId()))
                        .singleElement().satisfies(entry -> {
                            assertThat(entry.revealed()).isTrue();
                            assertThat(entry.highlighted()).isTrue();
                            assertThat(entry.sharedWith()).extracting(actor -> actor.personTmdbId())
                                    .containsExactly("501");
                        });
                assertThat(view.filmography().entries()).filteredOn(entry -> "200".equals(entry.workId()))
                        .singleElement().satisfies(entry -> {
                            assertThat(entry.revealed()).isTrue();
                            assertThat(entry.highlighted()).isFalse();
                            assertThat(entry.sharedWith()).isEmpty();
                            assertThat(entry.title()).isEqualTo("Unshared work");
                            assertThat(entry.posterUrl()).isEqualTo("/200.jpg");
                            assertThat(entry.character()).isEqualTo("Unshared Role");
                        });
            }
        }
    }

    @Test
    @DisplayName("[submitToday] Should Reject Invalid Image Before Mutation - When Challenge Path Is Invalid")
    void shouldRejectInvalidImageBeforeMutationWhenChallengePathIsInvalid() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER).toBuilder()
                .imagePath("https://evil.example/secret.jpg").build();
        when(challenges.findByChallengeDateAndGameType(DATE, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service().submitToday(USER, challenge.getGameType(),
                new DailyGameAttemptRequest("99", null, null, null, null)))
                .isInstanceOf(DailyGamesUnavailableException.class);

        verifyNoInteractions(results, media);
        verifyNoInteractions(mutations);
    }

    @Test
    @DisplayName("[giveUpToday] Should Reject Invalid Image Before Mutation - When Challenge Path Is Invalid")
    void shouldRejectInvalidImageBeforeMutationWhenGivingUpWithInvalidChallengePath() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER).toBuilder()
                .imagePath("https://evil.example/secret.jpg").build();
        when(challenges.findByChallengeDateAndGameType(DATE, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service().giveUpToday(USER, challenge.getGameType()))
                .isInstanceOf(DailyGamesUnavailableException.class);

        verifyNoInteractions(results, media);
        verifyNoInteractions(mutations);
    }

    @Test
    @DisplayName("[submitToday] Should Reject Invalid Frames Before Mutation - When Frame Set Is Incomplete")
    void shouldRejectInvalidFramesBeforeMutationWhenFrameSetIsIncomplete() {
        DailyChallenge challenge = challenge(DailyGameType.EPISODE_BY_FRAME).toBuilder()
                .displaySnapshot(Map.of("imagePaths", List.of("/frame0.jpg", "/frame1.jpg"))).build();
        when(challenges.findByChallengeDateAndGameType(DATE, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service().submitToday(USER, challenge.getGameType(),
                new DailyGameAttemptRequest(null, null, "43", 1, 1)))
                .isInstanceOf(DailyGamesUnavailableException.class);

        verifyNoInteractions(results, media);
        verifyNoInteractions(mutations);
    }

    @Test
    @DisplayName("[submitToday] Should Reject Invalid Filmography Before Mutation - When Snapshot Is Unusable")
    void shouldRejectInvalidFilmographyBeforeMutationWhenSnapshotIsUnusable() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY).toBuilder()
                .answerSnapshot(Map.of("filmography", List.of(Map.of(
                        "workId", "", "title", "No identity", "mediaType", "tv"))))
                .build();
        when(challenges.findByChallengeDateAndGameType(DATE, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service().submitToday(USER, challenge.getGameType(),
                new DailyGameAttemptRequest(null, "501", null, null, null)))
                .isInstanceOf(DailyGamesUnavailableException.class);

        verifyNoInteractions(results, media);
        verifyNoInteractions(mutations);
    }

    @Test
    @DisplayName("[giveUpToday] Should Reject Invalid Filmography Before Mutation - When Snapshot Is Unusable")
    void shouldRejectInvalidFilmographyBeforeMutationWhenGivingUpWithUnusableSnapshot() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY).toBuilder()
                .answerSnapshot(Map.of("filmography", List.of(Map.of(
                        "workId", "", "title", "No identity", "mediaType", "tv"))))
                .build();
        when(challenges.findByChallengeDateAndGameType(DATE, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service().giveUpToday(USER, challenge.getGameType()))
                .isInstanceOf(DailyGamesUnavailableException.class);

        verifyNoInteractions(results, media);
        verifyNoInteractions(mutations);
    }

    @Test
    void actionUsesCommittedMutationWithoutAnotherStateRead() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER);
        stubPreflight(challenge);
        UserDailyGameResult result = UserDailyGameResult.builder().dailyChallenge(challenge)
                .attemptsUsed(1).score(0).status(DailyGameResultStatus.IN_PROGRESS).build();
        DailyGameAttemptDTO current = new DailyGameAttemptDTO(1,
                new DailyGameCandidateDTO(DailyGameTargetKind.MOVIE, "99", null, null,
                        null, null, "Other", null, null), null, null, null);
        when(mutations.mutateAttempt(eq(USER), eq(DATE), eq(challenge.getGameType()), any(), eq(true), eq(true)))
                .thenReturn(new DailyGameServiceImpl.MutationResult(challenge, result, List.of(current),
                        current, null));
        when(media.issue(eq(USER), eq(challenge.getId()), any(), any(), anyString()))
                .thenAnswer(call -> new DailyGameMediaRefDTO(call.getArgument(2),
                        "/games/play/media/token", call.getArgument(3), true));

        var action = service().submitToday(USER, challenge.getGameType(),
                new DailyGameAttemptRequest("99", null, null, null, null));

        assertThat(action.state().attemptsUsed()).isEqualTo(1);
        assertThat(action.currentAttempt().candidate().tmdbId()).isEqualTo("99");
        verify(challenges).findByChallengeDateAndGameType(DATE, challenge.getGameType());
        verifyNoInteractions(results);
    }

    @Test
    void giveUpUsesCommittedZeroScoreAndHasNoCurrentAttempt() {
        DailyChallenge challenge = challenge(DailyGameType.EPISODE_BY_FRAME);
        stubPreflight(challenge);
        UserDailyGameResult result = UserDailyGameResult.builder().dailyChallenge(challenge)
                .attemptsUsed(1).score(0).status(DailyGameResultStatus.FAILED).build();
        when(mutations.mutateGiveUp(USER, DATE, challenge.getGameType()))
                .thenReturn(new DailyGameServiceImpl.MutationResult(challenge, result, List.of(), null, null));
        when(media.issue(eq(USER), eq(challenge.getId()), any(), any(), anyString()))
                .thenAnswer(call -> new DailyGameMediaRefDTO(call.getArgument(2),
                        "/games/play/media/token", call.getArgument(3), true));

        var action = service().giveUpToday(USER, challenge.getGameType());

        assertThat(action.state().score()).isZero();
        assertThat(action.state().media()).hasSize(6);
        assertThat(action.currentAttempt()).isNull();
        verify(challenges).findByChallengeDateAndGameType(DATE, challenge.getGameType());
        verifyNoInteractions(results);
    }

    private DailyGamePlayServiceImpl service() {
        return new DailyGamePlayServiceImpl(challenges, results, mutations, media,
                new DailyGameAttemptDetailsCodec(), new DailyGameFilmographyPlayAssembler(), CLOCK);
    }

    private void stubPreflight(DailyChallenge challenge) {
        when(challenges.findByChallengeDateAndGameType(DATE, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));
    }

    private DailyChallenge challenge(DailyGameType type) {
        List<String> frames = List.of("/frame0.jpg", "/frame1.jpg", "/frame2.jpg",
                "/frame3.jpg", "/frame4.jpg", "/frame5.jpg");
        return DailyChallenge.builder().id(UUID.randomUUID()).challengeDate(DATE).gameType(type)
                .targetKind(type.targetKind()).targetTmdbId("42").seriesTmdbId("43")
                .seasonNumber(1).episodeNumber(2).answerKey("MOVIE:42")
                .imagePath("/clue.jpg").answerSnapshot(Map.of("title", "Answer"))
                .displaySnapshot(type == DailyGameType.EPISODE_BY_FRAME
                        ? Map.of("imagePaths", frames) : Map.of("imagePath", "/clue.jpg"))
                .build();
    }
}
