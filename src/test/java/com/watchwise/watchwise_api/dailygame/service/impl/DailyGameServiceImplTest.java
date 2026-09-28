package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.DailyGamesUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameStateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameTodayResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallengeHint;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameResultStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeHintRepository;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeRepository;
import com.watchwise.watchwise_api.dailygame.repository.UserDailyGameResultRepository;
import com.watchwise.watchwise_api.dailygame.service.DailyGameCandidateIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyGameServiceImplTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 27);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 12, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private DailyChallengeRepository challengeRepository;

    @Mock
    private DailyChallengeHintRepository hintRepository;

    @Mock
    private UserDailyGameResultRepository resultRepository;

    @Mock
    private TmdbClient tmdbClient;

    @Test
    @DisplayName("[getToday] Should Return Eight NotPlayed States Without Creating Results - When The User Has No Results")
    void shouldReturnEightNotPlayedStatesWithoutCreatingResultsWhenTheUserHasNoResults() {
        List<DailyChallenge> challenges = allChallenges();
        when(challengeRepository.findByChallengeDateOrderByGameTypeAsc(TODAY)).thenReturn(challenges);
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(eq(USER_ID), any())).thenReturn(List.of());
        when(hintRepository.findByDailyChallengeIdInOrderByDailyChallengeIdAscPositionAsc(any())).thenReturn(allHints(challenges));

        DailyGameTodayResponseDTO response = service().getToday(USER_ID);

        assertThat(response.date()).isEqualTo(TODAY);
        assertThat(response.games()).hasSize(8);
        assertThat(response.games()).allSatisfy(game -> {
            assertThat(game.status()).isEqualTo(DailyGameViewStatus.NOT_PLAYED);
            assertThat(game.attemptsUsed()).isZero();
            assertThat(game.score()).isZero();
            assertThat(game.answer()).isNull();
        });
        verify(resultRepository).findByUserIdAndDailyChallengeIdIn(eq(USER_ID), eq(challenges.stream().map(DailyChallenge::getId).toList()));
        verify(hintRepository).findByDailyChallengeIdInOrderByDailyChallengeIdAscPositionAsc(eq(challenges.stream().map(DailyChallenge::getId).toList()));
        verify(resultRepository, never()).insertIfAbsent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("[getToday] Should Reject An Incomplete Daily Set - When Any Challenge Is Missing")
    void shouldRejectAnIncompleteDailySetWhenAnyChallengeIsMissing() {
        List<DailyChallenge> challenges = allChallenges().subList(0, 7);
        when(challengeRepository.findByChallengeDateOrderByGameTypeAsc(TODAY)).thenReturn(challenges);

        assertThatThrownBy(() -> service().getToday(USER_ID))
                .isInstanceOf(DailyGamesUnavailableException.class);
        verifyNoBatchReads();
    }

    @Test
    @DisplayName("[submitAttempt] Should Complete A Movie With Maximum Score - When The First Candidate Is Correct")
    void shouldCompleteAMovieWithMaximumScoreWhenTheFirstCandidateIsCorrect() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));

        DailyGameAttemptResponseDTO response = service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_POSTER,
                request("550", null, null, null, null));

        assertThat(response.status()).isEqualTo(DailyGameViewStatus.COMPLETED);
        assertThat(response.attemptsUsed()).isOne();
        assertThat(response.attemptsRemaining()).isEqualTo(5);
        assertThat(response.score()).isEqualTo(6);
        assertThat(response.answer()).isNotNull();
        assertThat(result.getAttemptsUsed()).isOne();
        assertThat(result.getStatus()).isEqualTo(DailyGameResultStatus.COMPLETED);
        assertThat(result.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("[submitAttempt] Should Assign Ten Points On The First Correct Info Guess - When The Candidate Matches A Ten Attempt Game")
    void shouldAssignTenPointsOnTheFirstCorrectInfoGuessWhenTheCandidateMatchesATenAttemptGame() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));

        DailyGameAttemptResponseDTO response = service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_INFO,
                request("550", null, null, null, null));

        assertThat(response.score()).isEqualTo(10);
        assertThat(response.status()).isEqualTo(DailyGameViewStatus.COMPLETED);
    }

    @Test
    @DisplayName("[submitAttempt] Should Complete With One Point - When The Correct Candidate Is Submitted On The Final Attempt")
    void shouldCompleteWithOnePointWhenTheCorrectCandidateIsSubmittedOnTheFinalAttempt() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult result = result(challenge, 9, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));

        DailyGameAttemptResponseDTO response = service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_INFO,
                request("550", null, null, null, null));

        assertThat(response.score()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(DailyGameViewStatus.COMPLETED);
        assertThat(response.attemptsUsed()).isEqualTo(10);
        assertThat(response.attemptsRemaining()).isZero();
        assertThat(response.answer()).isNotNull();
    }

    @Test
    @DisplayName("[submitAttempt] Should Keep The Game In Progress With Zero Score - When A Valid Candidate Is Wrong Before The Final Attempt")
    void shouldKeepTheGameInProgressWithZeroScoreWhenAValidCandidateIsWrongBeforeTheFinalAttempt() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getMovieFullDetails("680", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));

        DailyGameAttemptResponseDTO response = service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_INFO,
                request("680", null, null, null, null));

        assertThat(response.status()).isEqualTo(DailyGameViewStatus.IN_PROGRESS);
        assertThat(response.score()).isZero();
        assertThat(response.attemptsUsed()).isOne();
        assertThat(response.answer()).isNull();
        assertThat(result.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("[submitAttempt] Should Fail With Zero Score - When A Valid Candidate Is Wrong On The Final Attempt")
    void shouldFailWithZeroScoreWhenAValidCandidateIsWrongOnTheFinalAttempt() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER, "550");
        UserDailyGameResult result = result(challenge, 5, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getMovieFullDetails("680", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));

        DailyGameAttemptResponseDTO response = service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_POSTER,
                request("680", null, null, null, null));

        assertThat(response.status()).isEqualTo(DailyGameViewStatus.FAILED);
        assertThat(response.score()).isZero();
        assertThat(response.attemptsUsed()).isEqualTo(6);
        assertThat(response.attemptsRemaining()).isZero();
        assertThat(response.answer()).isNotNull();
        assertThat(result.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("[submitAttempt] Should Reject The Candidate Without Consuming An Attempt - When The Request Contains A Mismatched Field")
    void shouldRejectTheCandidateWithoutConsumingAnAttemptWhenTheRequestContainsAMismatchedField() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);

        assertThatThrownBy(() -> service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_POSTER,
                request(null, "287", null, null, null)))
                .isInstanceOf(BadRequestException.class);

        assertThat(result.getAttemptsUsed()).isZero();
        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[submitAttempt] Should Reject A Missing Candidate Without Consuming An Attempt - When TMDB Returns NotFound")
    void shouldRejectAMissingCandidateWithoutConsumingAnAttemptWhenTmdbReturnsNotFound() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getMovieFullDetails("680", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.NotFound<>());

        assertThatThrownBy(() -> service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_POSTER,
                request("680", null, null, null, null)))
                .isInstanceOf(BadRequestException.class);

        assertThat(result.getAttemptsUsed()).isZero();
    }

    @Test
    @DisplayName("[submitAttempt] Should Reject A Candidate With A 502 Error Without Consuming An Attempt - When TMDB Is Unavailable")
    void shouldRejectACandidateWithA502ErrorWithoutConsumingAnAttemptWhenTmdbIsUnavailable() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getMovieFullDetails("680", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_POSTER,
                request("680", null, null, null, null)))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.TmdbUnavailableException.class);

        assertThat(result.getAttemptsUsed()).isZero();
    }

    @Test
    @DisplayName("[answerKey] Should Normalize Every Supported Target Identity - When A Candidate Identity IsBuilt")
    void shouldNormalizeEverySupportedTargetIdentityWhenACandidateIdentityIsBuilt() {
        assertThat(new DailyGameCandidateIdentity(DailyGameTargetKind.MOVIE, "550", null, null, null, null)
                .answerKey()).isEqualTo("MOVIE:550");
        assertThat(new DailyGameCandidateIdentity(DailyGameTargetKind.SERIES, "1399", null, null, null, null)
                .answerKey()).isEqualTo("SERIES:1399");
        assertThat(new DailyGameCandidateIdentity(DailyGameTargetKind.PERSON, null, "287", null, null, null)
                .answerKey()).isEqualTo("PERSON:287");
        assertThat(new DailyGameCandidateIdentity(DailyGameTargetKind.EPISODE, null, null, "1396", 1, 2)
                .answerKey()).isEqualTo("EPISODE:1396:1:2");
    }

    @Test
    @DisplayName("[submitAttempt] Should Reject A Completed Result Before Validation - When The User Submits Again")
    void shouldRejectACompletedResultBeforeValidationWhenTheUserSubmitsAgain() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER, "550");
        UserDailyGameResult result = result(challenge, 1, 6, DailyGameResultStatus.COMPLETED, NOW);
        stubOpenSubmission(challenge, result);

        assertThatThrownBy(() -> service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_POSTER,
                request("680", null, null, null, null)))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[submitAttempt] Should Reject The Submission - When The Challenge Is Missing For Today")
    void shouldRejectTheSubmissionWhenTheChallengeIsMissingForToday() {
        when(challengeRepository.findByChallengeDateAndGameType(TODAY, DailyGameType.MOVIE_BY_POSTER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_POSTER,
                request("550", null, null, null, null)))
                .isInstanceOf(DailyGamesUnavailableException.class);
        verifyNoInteractions(resultRepository, tmdbClient);
    }

    @Test
    @DisplayName("[submitAttempt] Should Use The Composite Episode Identity - When The Candidate Is Valid")
    void shouldUseTheCompositeEpisodeIdentityWhenTheCandidateIsValid() {
        DailyChallenge challenge = challenge(DailyGameType.EPISODE_BY_FRAME, "1396", 1, 1);
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getEpisodeFullDetails("1396", 1, 1, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));

        DailyGameAttemptResponseDTO response = service().submitAttempt(USER_ID, DailyGameType.EPISODE_BY_FRAME,
                request(null, null, "1396", 1, 1));

        assertThat(response.status()).isEqualTo(DailyGameViewStatus.COMPLETED);
        assertThat(response.answer().seriesTmdbId()).isEqualTo("1396");
        assertThat(response.answer().seasonNumber()).isEqualTo(1);
        assertThat(response.answer().episodeNumber()).isEqualTo(1);
        verify(tmdbClient).getEpisodeFullDetails("1396", 1, 1, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }

    @Test
    @DisplayName("[getToday] Should Progress Information Hints Without Revealing The Answer - When The Result Is Open")
    void shouldProgressInformationHintsWithoutRevealingTheAnswerWhenTheResultIsOpen() {
        List<DailyChallenge> fullChallenges = allChallenges();
        DailyChallenge fullInfoChallenge = fullChallenges.stream()
                .filter(item -> item.getGameType() == DailyGameType.MOVIE_BY_INFO)
                .findFirst()
                .orElseThrow();
        UserDailyGameResult fullResult = result(fullInfoChallenge, 1, 0, DailyGameResultStatus.IN_PROGRESS, null);
        when(challengeRepository.findByChallengeDateOrderByGameTypeAsc(TODAY)).thenReturn(fullChallenges);
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(eq(USER_ID), any())).thenReturn(List.of(fullResult));
        when(hintRepository.findByDailyChallengeIdInOrderByDailyChallengeIdAscPositionAsc(any()))
                .thenReturn(List.of(
                        hint(fullInfoChallenge, 1, "YEAR", "1999"),
                        hint(fullInfoChallenge, 2, "GENRE", "Drama"),
                        hint(fullInfoChallenge, 3, "DIRECTOR", "David Fincher")));

        DailyGameStateDTO state = service().getToday(USER_ID).games().stream()
                .filter(item -> item.gameType() == DailyGameType.MOVIE_BY_INFO)
                .findFirst()
                .orElseThrow();

        assertThat(state.hints()).extracting(item -> item.position()).containsExactly(1, 2);
        assertThat(state.answer()).isNull();
    }

    @Test
    @DisplayName("[getToday] Should Reveal Every Hint And The Answer - When The Result Is Terminal")
    void shouldRevealEveryHintAndTheAnswerWhenTheResultIsTerminal() {
        List<DailyChallenge> challenges = allChallenges();
        DailyChallenge challenge = challenges.stream()
                .filter(item -> item.getGameType() == DailyGameType.MOVIE_BY_INFO)
                .findFirst()
                .orElseThrow();
        UserDailyGameResult result = result(challenge, 2, 8, DailyGameResultStatus.COMPLETED, NOW);
        when(challengeRepository.findByChallengeDateOrderByGameTypeAsc(TODAY)).thenReturn(challenges);
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(eq(USER_ID), any())).thenReturn(List.of(result));
        when(hintRepository.findByDailyChallengeIdInOrderByDailyChallengeIdAscPositionAsc(any()))
                .thenReturn(List.of(
                        hint(challenge, 1, "YEAR", "1999"),
                        hint(challenge, 2, "GENRE", "Drama"),
                        hint(challenge, 3, "DIRECTOR", "David Fincher")));

        DailyGameStateDTO state = service().getToday(USER_ID).games().stream()
                .filter(item -> item.gameType() == DailyGameType.MOVIE_BY_INFO)
                .findFirst()
                .orElseThrow();

        assertThat(state.status()).isEqualTo(DailyGameViewStatus.COMPLETED);
        assertThat(state.hints()).extracting(item -> item.position()).containsExactly(1, 2, 3);
        assertThat(state.answer()).isNotNull();
    }

    @Test
    @DisplayName("[submitAttempt] Should Serialize Insert And Locked Read - When Two First Submissions Race")
    void shouldSerializeInsertAndLockedReadWhenTwoFirstSubmissionsRace() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        when(challengeRepository.findByChallengeDateAndGameType(TODAY, DailyGameType.MOVIE_BY_POSTER))
                .thenReturn(Optional.of(challenge));
        when(resultRepository.findByUserIdAndDailyChallengeIdForUpdate(USER_ID, challenge.getId()))
                .thenReturn(Optional.of(result));
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));
        doAnswer(invocation -> {
            assertThat(result.getAttemptsUsed()).isZero();
            return 1;
        }).when(resultRepository).insertIfAbsent(any(), eq(USER_ID), eq(challenge.getId()), eq(NOW));

        service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_POSTER,
                request("550", null, null, null, null));

        InOrder order = inOrder(challengeRepository, resultRepository, tmdbClient);
        order.verify(challengeRepository).findByChallengeDateAndGameType(TODAY, DailyGameType.MOVIE_BY_POSTER);
        order.verify(resultRepository).insertIfAbsent(any(), eq(USER_ID), eq(challenge.getId()), eq(NOW));
        order.verify(resultRepository).findByUserIdAndDailyChallengeIdForUpdate(USER_ID, challenge.getId());
        order.verify(tmdbClient).getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }

    @Test
    @DisplayName("[submitAttempt] Should Require A Transaction - When The Submission Method Is Inspected")
    void shouldRequireATransactionWhenTheSubmissionMethodIsInspected() throws NoSuchMethodException {
        Method method = DailyGameServiceImpl.class.getMethod("submitAttempt", UUID.class, DailyGameType.class,
                com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest.class);
        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    private DailyGameServiceImpl service() {
        return new DailyGameServiceImpl(
                challengeRepository,
                hintRepository,
                resultRepository,
                new com.watchwise.watchwise_api.dailygame.service.DailyGameCandidateValidator(tmdbClient),
                new DailyChallengeResponseAssembler(),
                CLOCK);
    }

    private void stubOpenSubmission(DailyChallenge challenge, UserDailyGameResult result) {
        when(challengeRepository.findByChallengeDateAndGameType(TODAY, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));
        when(resultRepository.insertIfAbsent(any(), eq(USER_ID), eq(challenge.getId()), eq(NOW))).thenReturn(1);
        when(resultRepository.findByUserIdAndDailyChallengeIdForUpdate(USER_ID, challenge.getId()))
                .thenReturn(Optional.of(result));
    }

    private void verifyNoBatchReads() {
        verify(resultRepository, never()).findByUserIdAndDailyChallengeIdIn(any(), any());
        verify(hintRepository, never()).findByDailyChallengeIdInOrderByDailyChallengeIdAscPositionAsc(any());
    }

    private List<DailyChallenge> allChallenges() {
        List<DailyChallenge> challenges = new ArrayList<>();
        for (DailyGameType type : DailyGameType.values()) {
            challenges.add(challenge(type, type.targetKind() == DailyGameTargetKind.EPISODE ? "1396" : "550"));
        }
        return challenges;
    }

    private DailyChallenge challenge(DailyGameType type, String tmdbId) {
        if (type.targetKind() == DailyGameTargetKind.EPISODE) {
            return challenge(type, tmdbId, 1, 1);
        }
        UUID id = UUID.randomUUID();
        return DailyChallenge.builder()
                .id(id)
                .challengeDate(TODAY)
                .gameType(type)
                .targetKind(type.targetKind())
                .targetTmdbId(tmdbId)
                .answerKey(type.targetKind().name() + ":" + tmdbId)
                .imagePath("/image.jpg")
                .answerSnapshot(new ObjectMapper().createObjectNode()
                        .put("targetKind", type.targetKind().name())
                        .put("tmdbId", tmdbId)
                        .put("title", "Answer")
                        .put("imageUrl", "/image.jpg"))
                .displaySnapshot(new ObjectMapper().createObjectNode().put("imageUrl", "/image.jpg"))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private DailyChallenge challenge(DailyGameType type, String seriesTmdbId, int seasonNumber, int episodeNumber) {
        return DailyChallenge.builder()
                .id(UUID.randomUUID())
                .challengeDate(TODAY)
                .gameType(type)
                .targetKind(DailyGameTargetKind.EPISODE)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .answerKey("EPISODE:" + seriesTmdbId + ":" + seasonNumber + ":" + episodeNumber)
                .imagePath("/still.jpg")
                .answerSnapshot(new ObjectMapper().createObjectNode()
                        .put("targetKind", "EPISODE")
                        .put("seriesTmdbId", seriesTmdbId)
                        .put("seasonNumber", seasonNumber)
                        .put("episodeNumber", episodeNumber)
                        .put("title", "Episode")
                        .put("imageUrl", "/still.jpg"))
                .displaySnapshot(new ObjectMapper().createObjectNode().put("imageUrl", "/still.jpg"))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private UserDailyGameResult result(DailyChallenge challenge, int attempts, int score,
                                       DailyGameResultStatus status, LocalDateTime completedAt) {
        return UserDailyGameResult.builder()
                .id(UUID.randomUUID())
                .dailyChallenge(challenge)
                .attemptsUsed(attempts)
                .score(score)
                .status(status)
                .completedAt(completedAt)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private List<DailyChallengeHint> allHints(List<DailyChallenge> challenges) {
        return challenges.stream()
                .flatMap(challenge -> List.of(hint(challenge, 1, "YEAR", "1999")).stream())
                .toList();
    }

    private DailyChallengeHint hint(DailyChallenge challenge, int position, String type, String value) {
        return DailyChallengeHint.builder()
                .id(UUID.randomUUID())
                .dailyChallenge(challenge)
                .position(position)
                .hintType(type)
                .hintValue(value)
                .build();
    }

    private DailyGameAttemptRequest request(String tmdbId, String personTmdbId, String seriesTmdbId,
                                            Integer seasonNumber, Integer episodeNumber) {
        return new DailyGameAttemptRequest(tmdbId, personTmdbId, seriesTmdbId, seasonNumber, episodeNumber);
    }
}
