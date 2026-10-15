package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameMediaRefDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameCandidateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus;
import com.watchwise.watchwise_api.dailygame.entity.*;
import com.watchwise.watchwise_api.dailygame.media.*;
import com.watchwise.watchwise_api.dailygame.repository.*;
import com.watchwise.watchwise_api.dailygame.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    void rejectsNonVisualType() {
        assertThatThrownBy(() -> service().readToday(USER, DailyGameType.MOVIE_BY_INFO))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(challenges, results, media, mutations);
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

    @Test
    void actionUsesCommittedMutationWithoutAnotherStateRead() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER);
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
        verifyNoInteractions(challenges, results);
    }

    @Test
    void giveUpUsesCommittedZeroScoreAndHasNoCurrentAttempt() {
        DailyChallenge challenge = challenge(DailyGameType.EPISODE_BY_FRAME);
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
        verifyNoInteractions(challenges, results);
    }

    private DailyGamePlayServiceImpl service() {
        return new DailyGamePlayServiceImpl(challenges, results, mutations, media,
                new DailyGameAttemptDetailsCodec(), CLOCK);
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
