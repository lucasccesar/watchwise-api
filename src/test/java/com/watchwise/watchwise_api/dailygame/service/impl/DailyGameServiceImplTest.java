package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.DailyGamesUnavailableException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameComparisonCellDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameComparisonStatus;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameInfoFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameOverviewCardDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGamesOverviewDTO;
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
import com.watchwise.watchwise_api.dailygame.service.DailyGameInfoComparisonService;
import com.watchwise.watchwise_api.dailygame.service.DailyGameFilmographyService;
import com.watchwise.watchwise_api.dailygame.service.DailyGameMediaService;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameMediaRefDTO;
import com.watchwise.watchwise_api.dailygame.media.DailyGameImageVariant;
import com.watchwise.watchwise_api.dailygame.media.DailyGameMediaSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

    @Mock
    private DailyGameInfoComparisonService infoComparisonService;

    @Mock
    private DailyGameFilmographyService filmographyService;

    @Mock
    private DailyGameMediaService mediaService;

    @BeforeEach
    void stubOpaqueMediaGrants() {
        org.mockito.Mockito.lenient().when(mediaService.issue(any(), any(), any(), any(), any()))
                .thenAnswer(call -> new DailyGameMediaRefDTO(call.getArgument(2),
                        "/games/play/media/" + call.getArgument(2) + call.getArgument(4),
                        call.getArgument(3), true));
    }

    @Test
    void overviewUsesTheSameDistinctLegacyFrameSlotsAsPlay() {
        List<DailyChallenge> challenges = allChallenges().stream().map(challenge ->
                challenge.getGameType() == DailyGameType.EPISODE_BY_FRAME ? challenge.toBuilder()
                        .displaySnapshot(Map.of("imagePaths", List.of(" ", "/still-1.jpg", "/still-1.jpg",
                                "/still-2.jpg", "/still-3.jpg", "/still-4.jpg", "/still-5.jpg",
                                "/still-6.jpg", "/still-7.jpg"))).build() : challenge).toList();
        DailyChallenge episode = challenges.stream()
                .filter(challenge -> challenge.getGameType() == DailyGameType.EPISODE_BY_FRAME)
                .findFirst().orElseThrow();
        when(challengeRepository.findByChallengeDateOrderByGameTypeAsc(TODAY)).thenReturn(challenges);
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(
                USER_ID, challenges.stream().map(DailyChallenge::getId).toList()))
                .thenReturn(List.of(result(episode, 2, 0, DailyGameResultStatus.IN_PROGRESS, null)));
        when(mediaService.issue(eq(USER_ID), any(), any(), any(), any()))
                .thenAnswer(call -> new DailyGameMediaRefDTO(call.getArgument(2),
                        "/games/play/media/" + call.getArgument(2) + call.getArgument(4),
                        call.getArgument(3), true));

        var card = service().getTodayOverview(USER_ID).games().stream()
                .filter(game -> game.gameType() == DailyGameType.EPISODE_BY_FRAME).findFirst().orElseThrow();

        assertThat(card.visibleImageUrls()).containsExactly("/games/play/media/FRAME_0/still-1.jpg",
                "/games/play/media/FRAME_1/still-2.jpg", "/games/play/media/FRAME_2/still-3.jpg");

        when(resultRepository.findByUserIdAndDailyChallengeIdIn(
                USER_ID, challenges.stream().map(DailyChallenge::getId).toList()))
                .thenReturn(List.of(result(episode, 2, 4, DailyGameResultStatus.COMPLETED, NOW)));
        when(hintRepository.findByDailyChallengeIdInOrderByDailyChallengeIdAscPositionAsc(any()))
                .thenReturn(allHints(challenges));
        var raw = service().getToday(USER_ID).games().stream()
                .filter(game -> game.gameType() == DailyGameType.EPISODE_BY_FRAME).findFirst().orElseThrow();
        assertThat(raw.imageUrls()).hasSize(6)
                .allSatisfy(url -> assertThat(url).startsWith("/games/play/media/FRAME_"));
    }

    @ParameterizedTest
    @EnumSource(value = DailyGameType.class)
    void rawCurrentDayRouteAllowsRepeatingAValidCandidate(DailyGameType type) {
        DailyChallenge challenge = type == DailyGameType.EPISODE_BY_FRAME
                ? challenge(type, "1396", 1, 1) : challenge(type, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        DailyGameAttemptRequest guess = switch (type.targetKind()) {
            case MOVIE, SERIES -> request("551", null, null, null, null);
            case PERSON -> request(null, "551", null, null, null);
            case EPISODE -> request(null, null, "999", 1, 1);
        };
        switch (type.targetKind()) {
            case MOVIE -> when(tmdbClient.getMovieFullDetails(
                    "551", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                    .thenReturn(new TmdbLookupResult.Found<>(mock(TmdbMovieFullDetails.class)));
            case SERIES -> when(tmdbClient.getTvFullDetails(
                    "551", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                    .thenReturn(new TmdbLookupResult.Found<>(mock(TmdbTvFullDetails.class)));
            case PERSON -> when(tmdbClient.getPersonDetails("551"))
                    .thenReturn(new TmdbLookupResult.Found<>(mock(TmdbPersonDetails.class)));
            case EPISODE -> when(tmdbClient.getEpisodeFullDetails(
                    "999", 1, 1, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                    .thenReturn(new TmdbLookupResult.Found<>(mock(TmdbEpisodeFullDetails.class)));
        }

        DailyGameServiceImpl service = service();
        service.submitAttempt(USER_ID, type, guess);
        DailyGameAttemptResponseDTO second = service.submitAttempt(USER_ID, type, guess);

        assertThat(second.attemptsUsed()).isEqualTo(2);
        assertThat(result.getAttemptsUsed()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = DailyGameType.class)
    void shouldRejectRepeatedCurrentDayCandidateWithoutChangingResult(DailyGameType type) {
        DailyChallenge challenge = type == DailyGameType.EPISODE_BY_FRAME
                ? challenge(type, "1396", 1, 1) : challenge(type, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        DailyGameAttemptRequest guess = switch (type.targetKind()) {
            case MOVIE, SERIES -> request("551", null, null, null, null);
            case PERSON -> request(null, "551", null, null, null);
            case EPISODE -> request(null, null, "999", 1, 1);
        };
        switch (type.targetKind()) {
            case MOVIE -> when(tmdbClient.getMovieFullDetails("551", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                    .thenReturn(new TmdbLookupResult.Found<>(mock(TmdbMovieFullDetails.class)));
            case SERIES -> when(tmdbClient.getTvFullDetails("551", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                    .thenReturn(new TmdbLookupResult.Found<>(mock(TmdbTvFullDetails.class)));
            case PERSON -> when(tmdbClient.getPersonDetails("551"))
                    .thenReturn(new TmdbLookupResult.Found<>(mock(TmdbPersonDetails.class)));
            case EPISODE -> when(tmdbClient.getEpisodeFullDetails("999", 1, 1,
                    TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                    .thenReturn(new TmdbLookupResult.Found<>(mock(TmdbEpisodeFullDetails.class)));
        }

        DailyGameServiceImpl service = service();
        service.mutateAttempt(USER_ID, TODAY, type, guess, true, true);
        assertThatThrownBy(() -> service.mutateAttempt(USER_ID, TODAY, type,
                new DailyGameAttemptRequest(guess.tmdbId(), guess.personTmdbId(), guess.seriesTmdbId(),
                        guess.seasonNumber(), guess.episodeNumber(), true), true, true))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This candidate was already guessed");

        assertThat(result.getAttemptsUsed()).isEqualTo(1);
        assertThat(result.getScore()).isZero();
        assertThat(result.getStatus()).isEqualTo(DailyGameResultStatus.IN_PROGRESS);
        assertThat(result.isShareOnCompletion()).isFalse();
        assertThat(new com.watchwise.watchwise_api.dailygame.service.DailyGameAttemptDetailsCodec()
                .read(result.getAttemptDetails())).hasSize(1);
    }

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
    @DisplayName("[getDay] Should Return A Historical Daily Set - When The Requested Date Is In The Past")
    void shouldReturnAHistoricalDailySetWhenTheRequestedDateIsInThePast() {
        LocalDate historicalDate = TODAY.minusDays(1);
        List<DailyChallenge> challenges = allChallenges();
        when(challengeRepository.findByChallengeDateOrderByGameTypeAsc(historicalDate)).thenReturn(challenges);
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(eq(USER_ID), any())).thenReturn(List.of());
        when(hintRepository.findByDailyChallengeIdInOrderByDailyChallengeIdAscPositionAsc(any()))
                .thenReturn(allHints(challenges));

        DailyGameTodayResponseDTO response = service().getDay(USER_ID, historicalDate);

        assertThat(response.date()).isEqualTo(historicalDate);
        assertThat(response.games()).hasSize(8);
    }

    @Test
    @DisplayName("[getDay] Should Reject A Future Date - When The User Requests An Unavailable Day")
    void shouldRejectAFutureDateWhenTheUserRequestsAnUnavailableDay() {
        assertThatThrownBy(() -> service().getDay(USER_ID, TODAY.plusDays(1)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(challengeRepository, resultRepository, hintRepository);
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
    @DisplayName("[getTodayOverview] Should Return Eight Safe Cards And A UTC Reset - When The Daily Set Is Complete")
    void shouldReturnEightSafeCardsAndAUtcResetWhenTheDailySetIsComplete() {
        org.mockito.Mockito.lenient().when(mediaService.issue(eq(USER_ID), any(), any(), any(), any()))
                .thenAnswer(call -> new DailyGameMediaRefDTO(call.getArgument(2),
                        "/games/play/media/grant", call.getArgument(3), true));
        List<DailyChallenge> challenges = allChallenges();
        DailyChallenge completedChallenge = challenges.stream()
                .filter(item -> item.getGameType() == DailyGameType.MOVIE_BY_POSTER)
                .findFirst()
                .orElseThrow();
        DailyChallenge inProgressChallenge = challenges.stream()
                .filter(item -> item.getGameType() == DailyGameType.EPISODE_BY_FRAME)
                .findFirst()
                .orElseThrow();
        UserDailyGameResult completedResult = result(
                completedChallenge, 2, 5, DailyGameResultStatus.COMPLETED, NOW);
        UserDailyGameResult inProgressResult = result(
                inProgressChallenge, 1, 0, DailyGameResultStatus.IN_PROGRESS, null);
        inProgressResult.setAttemptDetails(Map.of("attempts", List.of("not decoded")));

        when(challengeRepository.findByChallengeDateOrderByGameTypeAsc(TODAY)).thenReturn(challenges);
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(eq(USER_ID), eq(challenges.stream()
                .map(DailyChallenge::getId).toList())))
                .thenReturn(List.of(completedResult, inProgressResult));

        DailyGamesOverviewDTO response = service().getTodayOverview(USER_ID);

        assertThat(response.date()).isEqualTo(TODAY);
        assertThat(response.nextResetAt()).isEqualTo(Instant.parse("2026-09-28T00:00:00Z"));
        assertThat(response.totalMaxScore()).isEqualTo(64);
        assertThat(response.currentScore()).isEqualTo(5);
        assertThat(response.games()).extracting(DailyGameOverviewCardDTO::gameType)
                .containsExactly(DailyGameType.values());

        DailyGameOverviewCardDTO inProgressCard = response.games().stream()
                .filter(item -> item.gameType() == DailyGameType.EPISODE_BY_FRAME)
                .findFirst()
                .orElseThrow();
        assertThat(inProgressCard.attemptsUsed()).isEqualTo(1);
        assertThat(inProgressCard.attemptsRemaining()).isEqualTo(5);
        assertThat(inProgressCard.status()).isEqualTo(DailyGameViewStatus.IN_PROGRESS);
        assertThat(inProgressCard.score()).isZero();
        assertThat(inProgressCard.imageUrl()).isEqualTo("/games/play/media/grant");
        assertThat(inProgressCard.visibleImageUrls()).containsExactly(
                "/games/play/media/grant", "/games/play/media/grant");
        assertThat(response.games()).filteredOn(item -> item.status() != DailyGameViewStatus.COMPLETED
                        && item.status() != DailyGameViewStatus.FAILED && item.imageUrl() != null)
                .allSatisfy(item -> {
                    assertThat(item.imageUrl()).startsWith("/games/play/media/");
                    assertThat(item.visibleImageUrls()).allSatisfy(url ->
                            assertThat(url).startsWith("/games/play/media/"));
                });
        assertThat(response.games()).filteredOn(item -> item.gameType() == DailyGameType.MOVIE_BY_INFO
                        || item.gameType() == DailyGameType.SERIES_BY_INFO
                        || item.gameType() == DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY
                        || item.gameType() == DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY)
                .allSatisfy(item -> {
                    assertThat(item.imageUrl()).isNull();
                    assertThat(item.visibleImageUrls()).isEmpty();
                });

        verify(resultRepository).findByUserIdAndDailyChallengeIdIn(
                USER_ID, challenges.stream().map(DailyChallenge::getId).toList());
        verifyNoInteractions(hintRepository);
    }

    @Test
    @DisplayName("[getDay/getToday/overview] Should Preserve Raw Repository Order And Sort Only The Overview")
    void shouldPreserveRawRepositoryOrderAndSortOnlyTheOverview() {
        when(mediaService.issue(eq(USER_ID), any(), any(), any(), any()))
                .thenAnswer(call -> new DailyGameMediaRefDTO(call.getArgument(2),
                        "/games/play/media/grant", call.getArgument(3), true));
        List<DailyChallenge> challenges = allChallenges();
        List<DailyChallenge> shuffledChallenges = List.of(
                challenges.get(3), challenges.get(0), challenges.get(7), challenges.get(2),
                challenges.get(5), challenges.get(1), challenges.get(6), challenges.get(4));
        List<DailyGameType> repositoryOrder = shuffledChallenges.stream()
                .map(DailyChallenge::getGameType)
                .toList();
        when(challengeRepository.findByChallengeDateOrderByGameTypeAsc(TODAY)).thenReturn(shuffledChallenges);
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(eq(USER_ID), any())).thenReturn(List.of());
        when(hintRepository.findByDailyChallengeIdInOrderByDailyChallengeIdAscPositionAsc(any()))
                .thenReturn(allHints(shuffledChallenges));

        DailyGameTodayResponseDTO historicalRaw = service().getDay(USER_ID, TODAY);
        DailyGameTodayResponseDTO todayRaw = service().getToday(USER_ID);
        DailyGamesOverviewDTO overview = service().getTodayOverview(USER_ID);

        assertThat(historicalRaw.games()).extracting(DailyGameStateDTO::gameType)
                .containsExactlyElementsOf(repositoryOrder);
        assertThat(todayRaw.games()).extracting(DailyGameStateDTO::gameType)
                .containsExactlyElementsOf(repositoryOrder);
        assertThat(overview.games()).extracting(DailyGameOverviewCardDTO::gameType)
                .containsExactly(DailyGameType.values());
    }

    @Test
    @DisplayName("[getTodayOverview] Should Reject An Incomplete Daily Set - When Any Challenge Is Missing")
    void shouldRejectAnIncompleteDailySetWhenOverviewIsRequested() {
        when(challengeRepository.findByChallengeDateOrderByGameTypeAsc(TODAY))
                .thenReturn(allChallenges().subList(0, 7));

        assertThatThrownBy(() -> service().getTodayOverview(USER_ID))
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
    @DisplayName("[submitAttempt] Should Accept A Historical Date - When The User Has Not Finished That Game")
    void shouldAcceptAHistoricalDateWhenTheUserHasNotFinishedThatGame() {
        LocalDate historicalDate = TODAY.minusDays(1);
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        when(challengeRepository.findByChallengeDateAndGameType(historicalDate, DailyGameType.MOVIE_BY_POSTER))
                .thenReturn(Optional.of(challenge));
        when(resultRepository.insertIfAbsent(any(), eq(USER_ID), eq(challenge.getId()), eq(NOW))).thenReturn(1);
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(eq(USER_ID), any()))
                .thenReturn(List.of(result));
        when(resultRepository.findByUserIdAndDailyChallengeIdForUpdate(USER_ID, challenge.getId()))
                .thenReturn(Optional.of(result));
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));

        DailyGameAttemptResponseDTO response = service().submitAttempt(
                USER_ID, historicalDate, DailyGameType.MOVIE_BY_POSTER,
                request("550", null, null, null, null));

        assertThat(response.status()).isEqualTo(DailyGameViewStatus.COMPLETED);
    }

    @Test
    @DisplayName("[submitAttempt] Should Return Only The Current Attempt - When The Challenge Is Historical")
    void shouldReturnOnlyTheCurrentAttemptWhenTheChallengeIsHistorical() {
        LocalDate historicalDate = TODAY.minusDays(1);
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550", historicalDate);
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        when(challengeRepository.findByChallengeDateAndGameType(historicalDate, DailyGameType.MOVIE_BY_INFO))
                .thenReturn(Optional.of(challenge));
        when(resultRepository.insertIfAbsent(any(), eq(USER_ID), eq(challenge.getId()), eq(NOW))).thenReturn(1);
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(USER_ID, List.of(challenge.getId())))
                .thenReturn(List.of(result));
        org.mockito.Mockito.lenient()
                .when(resultRepository.findByUserIdAndDailyChallengeIdForUpdate(USER_ID, challenge.getId()))
                .thenReturn(Optional.of(result));
        when(tmdbClient.getMovieFullDetails("680", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbMovieFullDetails(
                        "680", "Pulp Fiction", null, null, "/pulp.jpg", null, "1994-09-10", 154,
                        List.of(), List.of(), null, null, null, null, null, List.of(), null)));
        when(infoComparisonService.compare(eq(challenge), any())).thenReturn(infoFeedback());

        DailyGameAttemptResponseDTO response = service().submitAttempt(
                USER_ID, historicalDate, DailyGameType.MOVIE_BY_INFO,
                request("680", null, null, null, null));
        assertThat(response.attempts()).isNull();
        assertThat(result.getAttemptDetails()).isNull();
        assertThat(response.currentAttempt().attemptNumber()).isOne();
        assertThat(response.currentAttempt().candidate().tmdbId()).isEqualTo("680");
        assertThat(response.currentAttempt().infoFeedback()).isNotNull();
        assertThat(response.currentAttempt().filmographyFeedback()).isNull();
    }

    @Test
    @DisplayName("[submitAttempt] Should Share Completed Result - When Sharing Is Enabled On The Final Attempt")
    void shouldShareCompletedResultWhenToggleIsEnabledOnFinalAttempt() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));

        DailyGameAttemptResponseDTO response = service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_INFO,
                request("550", null, null, null, null, true));

        assertThat(response.status()).isEqualTo(DailyGameViewStatus.COMPLETED);
        assertThat(response.sharedToFeed()).isTrue();
        assertThat(result.getSharedAt()).isEqualTo(NOW);
        assertThat(result.isShareOnCompletion()).isTrue();
    }

    @Test
    @DisplayName("[submitAttempt] Should Share Failed Result - When Sharing Is Enabled On The Final Attempt")
    void shouldShareFailedResultWhenToggleIsEnabledOnFinalAttempt() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult result = result(challenge, 9, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getMovieFullDetails("680", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));

        DailyGameAttemptResponseDTO response = service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_INFO,
                request("680", null, null, null, null, true));

        assertThat(response.status()).isEqualTo(DailyGameViewStatus.FAILED);
        assertThat(response.sharedToFeed()).isTrue();
        assertThat(result.getSharedAt()).isNotNull();
    }

    @Test
    @DisplayName("[submitAttempt] Should Persist Sharing Preference Until Terminal - When A Later Request Omits It")
    void shouldPersistToggleUntilTheResultBecomesTerminal() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getMovieFullDetails("680", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));

        DailyGameAttemptResponseDTO firstResponse = service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_INFO,
                request("680", null, null, null, null, true));
        DailyGameAttemptResponseDTO finalResponse = service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_INFO,
                request("550", null, null, null, null, null));

        assertThat(firstResponse.status()).isEqualTo(DailyGameViewStatus.IN_PROGRESS);
        assertThat(firstResponse.sharedToFeed()).isFalse();
        assertThat(finalResponse.sharedToFeed()).isTrue();
        assertThat(result.getSharedAt()).isEqualTo(NOW);
        assertThat(result.isShareOnCompletion()).isTrue();
    }

    @Test
    @DisplayName("[submitAttempt] Should Not Share Terminal Result - When Sharing Is Disabled On The Final Attempt")
    void shouldNotShareTerminalResultWhenToggleIsDisabled() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));

        DailyGameAttemptResponseDTO response = service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_INFO,
                request("550", null, null, null, null, false));

        assertThat(response.status()).isEqualTo(DailyGameViewStatus.COMPLETED);
        assertThat(response.sharedToFeed()).isFalse();
        assertThat(result.getSharedAt()).isNull();
        assertThat(result.isShareOnCompletion()).isFalse();
    }

    @Test
    @DisplayName("[submitAttempt] Should Leave Sharing Preference Untouched - When Candidate Validation Fails")
    void shouldLeaveSharingPreferenceUntouchedWhenCandidateIsInvalid() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);

        assertThatThrownBy(() -> service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_POSTER,
                request(null, "287", null, null, null, true)))
                .isInstanceOf(BadRequestException.class);

        assertThat(result.isShareOnCompletion()).isFalse();
        assertThat(result.getSharedAt()).isNull();
        assertThat(result.getAttemptsUsed()).isZero();
    }

    @Test
    @DisplayName("[submitAttempt] Should Assign Ten Points On The First Correct Info Guess - When The Candidate Matches A Ten Attempt Game")
    void shouldAssignTenPointsOnTheFirstCorrectInfoGuessWhenTheCandidateMatchesATenAttemptGame() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));
        when(infoComparisonService.compare(eq(challenge), any())).thenReturn(infoFeedback());

        DailyGameAttemptResponseDTO response = service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_INFO,
                request("550", null, null, null, null));

        assertThat(response.score()).isEqualTo(10);
        assertThat(response.status()).isEqualTo(DailyGameViewStatus.COMPLETED);
        assertThat(response.attempts()).hasSize(1);
        assertThat(response.attempts().getFirst().infoFeedback()).isEqualTo(infoFeedback());
    }

    @Test
    @DisplayName("[submitAttempt] Should Preserve Attempts And Details - When Info Comparison Cannot Reach TMDB")
    void shouldPreserveAttemptsAndDetailsWhenInfoComparisonCannotReachTmdb() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        Map<String, Object> existingDetails = Map.of("attempts", List.of("existing"));
        result.setAttemptDetails(existingDetails);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getMovieFullDetails("680", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));
        when(infoComparisonService.compare(eq(challenge), any()))
                .thenThrow(new TmdbUnavailableException("TMDB is temporarily unavailable"));

        assertThatThrownBy(() -> service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_INFO,
                request("680", null, null, null, null)))
                .isInstanceOf(TmdbUnavailableException.class);

        assertThat(result.getAttemptsUsed()).isZero();
        assertThat(result.getAttemptDetails()).isSameAs(existingDetails);
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
        Map<String, Object> existingDetails = Map.of("attempts", List.of("existing"));
        result.setAttemptDetails(existingDetails);
        stubOpenSubmission(challenge, result);

        assertThatThrownBy(() -> service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_POSTER,
                request(null, "287", null, null, null)))
                .isInstanceOf(BadRequestException.class);

        assertThat(result.getAttemptsUsed()).isZero();
        assertThat(result.getAttemptDetails()).isSameAs(existingDetails);
        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[giveUp] Should Reject A Terminal Result - When The User Gives Up After Finishing")
    void shouldRejectAGiveUpWhenTheResultIsAlreadyTerminal() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult result = result(challenge, 1, 10, DailyGameResultStatus.COMPLETED, NOW);
        when(challengeRepository.findByChallengeDateAndGameType(TODAY, DailyGameType.MOVIE_BY_INFO))
                .thenReturn(Optional.of(challenge));
        when(resultRepository.insertIfAbsent(any(), eq(USER_ID), eq(challenge.getId()), eq(NOW))).thenReturn(0);
        org.mockito.Mockito.lenient()
                .when(resultRepository.findByUserIdAndDailyChallengeIdForUpdate(USER_ID, challenge.getId()))
                .thenReturn(Optional.of(result));

        assertThatThrownBy(() -> service().giveUp(USER_ID, DailyGameType.MOVIE_BY_INFO))
                .isInstanceOf(ConflictException.class);

        assertThat(result.getStatus()).isEqualTo(DailyGameResultStatus.COMPLETED);
        assertThat(result.getAttemptsUsed()).isOne();
        assertThat(result.getScore()).isEqualTo(10);
        verifyNoInteractions(hintRepository);
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
    @DisplayName("[answerKey] Should Normalize Every Supported Target Identity - When A Candidate Identity Is Built")
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
    @DisplayName("[giveUp] Should Share The Failed Result - When Sharing Was Enabled Before Giving Up")
    void shouldShareTheFailedResultWhenSharingWasEnabledBeforeGivingUp() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult result = result(challenge, 2, 0, DailyGameResultStatus.IN_PROGRESS, null);
        result.setShareOnCompletion(true);
        stubGiveUp(challenge, result);

        DailyGameAttemptResponseDTO response = service().giveUp(USER_ID, DailyGameType.MOVIE_BY_INFO);

        assertThat(response.status()).isEqualTo(DailyGameViewStatus.FAILED);
        assertThat(response.sharedToFeed()).isTrue();
        assertThat(result.getSharedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("[giveUp] Should Not Share The Failed Result - When Sharing Was Not Enabled")
    void shouldNotShareTheFailedResultWhenSharingWasNotEnabled() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult result = result(challenge, 2, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubGiveUp(challenge, result);

        DailyGameAttemptResponseDTO response = service().giveUp(USER_ID, DailyGameType.MOVIE_BY_INFO);

        assertThat(response.status()).isEqualTo(DailyGameViewStatus.FAILED);
        assertThat(response.sharedToFeed()).isFalse();
        assertThat(result.getSharedAt()).isNull();
    }

    private void stubGiveUp(DailyChallenge challenge, UserDailyGameResult result) {
        when(challengeRepository.findByChallengeDateAndGameType(TODAY, DailyGameType.MOVIE_BY_INFO))
                .thenReturn(Optional.of(challenge));
        when(resultRepository.insertIfAbsent(any(), eq(USER_ID), eq(challenge.getId()), eq(NOW))).thenReturn(1);
        when(resultRepository.findByUserIdAndDailyChallengeIdForUpdate(USER_ID, challenge.getId()))
                .thenReturn(Optional.of(result));
        when(hintRepository.findByDailyChallengeIdOrderByPositionAsc(challenge.getId())).thenReturn(List.of());
    }

    @Test
    @DisplayName("[giveUp] Should Fail Without Consuming An Attempt - When The User Gives Up Before Playing")
    void shouldFailWithoutConsumingAnAttemptWhenTheUserGivesUp() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        when(challengeRepository.findByChallengeDateAndGameType(TODAY, DailyGameType.MOVIE_BY_INFO))
                .thenReturn(Optional.of(challenge));
        when(resultRepository.insertIfAbsent(any(), eq(USER_ID), eq(challenge.getId()), eq(NOW))).thenReturn(1);
        when(resultRepository.findByUserIdAndDailyChallengeIdForUpdate(USER_ID, challenge.getId()))
                .thenReturn(Optional.of(result));
        org.mockito.Mockito.lenient().when(resultRepository.findByUserIdAndDailyChallengeIdIn(USER_ID, List.of(challenge.getId())))
                .thenReturn(List.of(result));
        when(hintRepository.findByDailyChallengeIdOrderByPositionAsc(challenge.getId())).thenReturn(List.of());

        DailyGameAttemptResponseDTO response = service().giveUp(USER_ID, DailyGameType.MOVIE_BY_INFO);

        assertThat(response.status()).isEqualTo(DailyGameViewStatus.FAILED);
        assertThat(response.attemptsUsed()).isZero();
        assertThat(response.score()).isZero();
        assertThat(response.answer()).isNotNull();
        assertThat(response.answer().targetKind()).isEqualTo(DailyGameTargetKind.MOVIE);
        assertThat(response.answer().tmdbId()).isEqualTo("550");
        assertThat(response.answer().personTmdbId()).isNull();
        assertThat(response.answer().seriesTmdbId()).isNull();
        assertThat(response.answer().title()).isEqualTo("Answer");
        assertThat(response.answer().imageUrl()).isEqualTo("/games/play/media/CLUE/image.jpg");
        assertThat(result.getStatus()).isEqualTo(DailyGameResultStatus.FAILED);
        assertThat(result.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("[getGame] Should Return A NotPlayed State - When The User Has No Result For The Game")
    void shouldReturnANotPlayedStateWhenTheUserHasNoResultForTheGame() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        when(challengeRepository.findByChallengeDateAndGameType(TODAY, DailyGameType.MOVIE_BY_INFO))
                .thenReturn(Optional.of(challenge));
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(eq(USER_ID), eq(List.of(challenge.getId()))))
                .thenReturn(List.of());
        when(hintRepository.findByDailyChallengeIdOrderByPositionAsc(challenge.getId())).thenReturn(List.of());

        DailyGameStateDTO response = service().getGame(
                USER_ID, TODAY, DailyGameType.MOVIE_BY_INFO, true);

        assertThat(response.status()).isEqualTo(DailyGameViewStatus.NOT_PLAYED);
        assertThat(response.attemptsUsed()).isZero();
        assertThat(response.answer()).isNull();
    }

    @Test
    @DisplayName("[getGame] Should Keep Frozen Period Out Of Raw Filmography - When The Visual Route Exposes It")
    void shouldKeepFrozenPeriodOutOfRawFilmographyWhenTheVisualRouteExposesIt() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY, "1").toBuilder()
                .answerSnapshot(Map.of("filmography", List.of(Map.of(
                        "workId", "77", "title", "Frozen Series", "mediaType", "tv",
                        "year", 2018, "genres", List.of("Drama"), "episodeCount", 8,
                        "totalEpisodes", 8, "period", "2018-01-01/2022-01-01"))))
                .build();
        when(challengeRepository.findByChallengeDateAndGameType(
                TODAY, DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY)).thenReturn(Optional.of(challenge));
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(USER_ID, List.of(challenge.getId())))
                .thenReturn(List.of());
        when(hintRepository.findByDailyChallengeIdOrderByPositionAsc(challenge.getId())).thenReturn(List.of());

        DailyGameStateDTO response = service().getGame(
                USER_ID, TODAY, DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY, true);

        assertThat(response.filmography().entries()).singleElement().satisfies(entry -> {
            assertThat(entry.title()).isNull();
            assertThat(entry.period()).isNull();
        });
        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[submitAttempt] Should Preserve Season And Episode Display Metadata - When A Series Guess Is Compared")
    void shouldPreserveSeasonAndEpisodeDisplayMetadataWhenASeriesGuessIsCompared() {
        DailyChallenge challenge = challenge(DailyGameType.SERIES_BY_INFO, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        TmdbTvFullDetails candidate = mock(TmdbTvFullDetails.class);
        when(tmdbClient.getTvFullDetails("551", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(candidate));
        DailyGameComparisonCellDTO seasons = new DailyGameComparisonCellDTO(
                DailyGameComparisonStatus.NO_MATCH, null, 3, List.of(), null);
        DailyGameInfoFeedbackDTO feedback = new DailyGameInfoFeedbackDTO(
                null, null, null, null, null, null, null, seasons, 24);
        when(infoComparisonService.compare(eq(challenge), any())).thenReturn(feedback);

        DailyGameAttemptResponseDTO response = service().submitAttempt(
                USER_ID, DailyGameType.SERIES_BY_INFO, request("551", null, null, null, null));

        assertThat(response.currentAttempt().infoFeedback().revenueOrSeasons().displayValue()).isEqualTo(3);
        assertThat(response.currentAttempt().infoFeedback().candidateEpisodeCount()).isEqualTo(24);
    }

    @Test
    @DisplayName("[submitAttempt] Should Persist Candidate Metadata - When The Validated Movie Has Metadata")
    void shouldPersistCandidateMetadataWhenTheValidatedMovieHasMetadata() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getMovieFullDetails("680", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbMovieFullDetails(
                        "680", "Pulp Fiction", null, null, "/pulp.jpg", null, "1994-09-10", 154,
                        List.of(), List.of(), null, null, null, null, null, List.of(), null)));

        DailyGameAttemptResponseDTO response = service().submitAttempt(USER_ID, DailyGameType.MOVIE_BY_POSTER,
                request("680", null, null, null, null));

        assertThat(response.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.candidate().title()).isEqualTo("Pulp Fiction");
            assertThat(attempt.candidate().imageUrl()).isEqualTo("https://image.tmdb.org/t/p/w500/pulp.jpg");
            assertThat(attempt.candidate().date()).isEqualTo(LocalDate.of(1994, 9, 10));
        });
    }

    @Test
    @DisplayName("[getGameToday] Should Resolve The Date With The Injected Clock - When The Current Game Is Requested")
    void shouldResolveTheDateWithTheInjectedClockWhenTheCurrentGameIsRequested() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_INFO, "550");
        when(challengeRepository.findByChallengeDateAndGameType(TODAY, DailyGameType.MOVIE_BY_INFO))
                .thenReturn(Optional.of(challenge));
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(USER_ID, List.of(challenge.getId())))
                .thenReturn(List.of());
        when(hintRepository.findByDailyChallengeIdOrderByPositionAsc(challenge.getId())).thenReturn(List.of());

        service().getGameToday(USER_ID, DailyGameType.MOVIE_BY_INFO, true);

        verify(challengeRepository).findByChallengeDateAndGameType(TODAY, DailyGameType.MOVIE_BY_INFO);
    }

    @Test
    @DisplayName("[submitAttempt] Should Persist Filmography Feedback - When A Person Guess Is Submitted")
    void shouldPersistFilmographyFeedbackWhenAPersonGuessIsSubmitted() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY, "1");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getPersonDetails("2"))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));
        DailyGameFilmographyFeedbackDTO feedback = new DailyGameFilmographyFeedbackDTO(
                new com.watchwise.watchwise_api.dailygame.dto.DailyGameActorGuessDTO("2", "Guessed Actor"),
                List.of("SERIES:10"), List.of("SERIES:10", "SERIES:20"), List.of());
        when(filmographyService.compare(challenge, "2", false)).thenReturn(feedback);

        DailyGameAttemptResponseDTO response = service().submitAttempt(
                USER_ID, DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY,
                request(null, "2", null, null, null), false);

        assertThat(response.attempts()).singleElement().satisfies(attempt ->
                assertThat(attempt.filmographyFeedback()).isEqualTo(feedback));
        verify(filmographyService).compare(challenge, "2", false);
    }

    @Test
    @DisplayName("[getGame] Should Assemble A Terminal Episode Answer From The Snapshot - Without Calling TMDB")
    void shouldAssembleATerminalEpisodeAnswerFromTheSnapshotWithoutCallingTmdb() {
        DailyChallenge challenge = challenge(DailyGameType.EPISODE_BY_FRAME, "1396", 1, 1);
        UserDailyGameResult result = result(challenge, 1, 6, DailyGameResultStatus.COMPLETED, NOW);
        when(challengeRepository.findByChallengeDateAndGameType(TODAY, DailyGameType.EPISODE_BY_FRAME))
                .thenReturn(Optional.of(challenge));
        org.mockito.Mockito.lenient().when(resultRepository.findByUserIdAndDailyChallengeIdIn(USER_ID, List.of(challenge.getId())))
                .thenReturn(List.of(result));
        when(hintRepository.findByDailyChallengeIdOrderByPositionAsc(challenge.getId())).thenReturn(List.of());

        DailyGameStateDTO response = service().getGame(
                USER_ID, TODAY, DailyGameType.EPISODE_BY_FRAME, false);

        assertThat(response.answer()).satisfies(answer -> {
            assertThat(answer.seriesName()).isEqualTo("Breaking Bad");
            assertThat(answer.seriesPosterUrl()).isEqualTo("/games/play/media/SERIES_POSTER/poster.jpg");
            assertThat(answer.seriesYear()).isEqualTo(2008);
        });
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void legacyGameStateUsesTheSameBlurredMediaGrantAsThePlayProjection() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER, "550");
        UserDailyGameResult result = result(challenge, 2, 0, DailyGameResultStatus.IN_PROGRESS, null);
        String mediaUrl = "/games/play/media/opaque-grant";
        when(challengeRepository.findByChallengeDateAndGameType(TODAY, DailyGameType.MOVIE_BY_POSTER))
                .thenReturn(Optional.of(challenge));
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(USER_ID, List.of(challenge.getId())))
                .thenReturn(List.of(result));
        when(hintRepository.findByDailyChallengeIdOrderByPositionAsc(challenge.getId())).thenReturn(List.of());
        when(mediaService.issue(USER_ID, challenge.getId(), DailyGameMediaSlot.CLUE,
                DailyGameImageVariant.BLUR_14, "/image.jpg"))
                .thenReturn(new DailyGameMediaRefDTO(DailyGameMediaSlot.CLUE, mediaUrl,
                        DailyGameImageVariant.BLUR_14, true));

        DailyGameStateDTO response = service().getGame(
                USER_ID, TODAY, DailyGameType.MOVIE_BY_POSTER, true);

        assertThat(response.imageUrl()).isEqualTo(mediaUrl);
        assertThat(response.visibleImageUrls()).containsExactly(mediaUrl);
        assertThat(response.imageUrl()).doesNotContain("image.tmdb.org");
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
        assertThat(response.guessFeedback()).satisfies(feedback -> {
            assertThat(feedback.seriesCorrect()).isTrue();
            assertThat(feedback.seasonCorrect()).isTrue();
            assertThat(feedback.episodeCorrect()).isTrue();
            assertThat(feedback.exactMatch()).isTrue();
        });
        verify(tmdbClient).getEpisodeFullDetails("1396", 1, 1, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }

    @Test
    @DisplayName("[submitAttempt] Should Report Independent Episode Field Matches - When The Composite Guess Is Partially Correct")
    void shouldReportIndependentEpisodeFieldMatchesWhenTheCompositeGuessIsPartiallyCorrect() {
        DailyChallenge challenge = challenge(DailyGameType.EPISODE_BY_FRAME, "1396", 1, 1);
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        stubOpenSubmission(challenge, result);
        when(tmdbClient.getEpisodeFullDetails("999", 1, 1, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(mock()));

        DailyGameAttemptResponseDTO response = service().submitAttempt(USER_ID, DailyGameType.EPISODE_BY_FRAME,
                request(null, null, "999", 1, 1));

        assertThat(response.status()).isEqualTo(DailyGameViewStatus.IN_PROGRESS);
        assertThat(response.guessFeedback()).satisfies(feedback -> {
            assertThat(feedback.seriesCorrect()).isFalse();
            assertThat(feedback.seasonCorrect()).isTrue();
            assertThat(feedback.episodeCorrect()).isTrue();
            assertThat(feedback.exactMatch()).isFalse();
        });
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

        assertThat(state.hints()).isEmpty();
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
        assertThat(state.hints()).isEmpty();
        assertThat(state.answer()).isNotNull();
    }

    @Test
    @DisplayName("[submitAttempt] Should Lock Only After TMDB Validation - When Two First Submissions Race")
    void shouldLockOnlyAfterTmdbValidationWhenTwoFirstSubmissionsRace() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER, "550");
        UserDailyGameResult result = result(challenge, 0, 0, DailyGameResultStatus.IN_PROGRESS, null);
        when(challengeRepository.findByChallengeDateAndGameType(TODAY, DailyGameType.MOVIE_BY_POSTER))
                .thenReturn(Optional.of(challenge));
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(eq(USER_ID), any()))
                .thenReturn(List.of(result));
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
        order.verify(resultRepository).findByUserIdAndDailyChallengeIdIn(USER_ID, List.of(challenge.getId()));
        order.verify(tmdbClient).getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
        order.verify(resultRepository).insertIfAbsent(any(), eq(USER_ID), eq(challenge.getId()), eq(NOW));
        order.verify(resultRepository).findByUserIdAndDailyChallengeIdForUpdate(USER_ID, challenge.getId());
    }

    @Test
    @DisplayName("[submitAttempt] Should Not Hold A Method Transaction - When TMDB Work Must Precede Persistence")
    void shouldNotHoldAMethodTransactionWhenTmdbWorkMustPrecedePersistence() throws NoSuchMethodException {
        Method method = DailyGameServiceImpl.class.getMethod("submitAttempt", UUID.class, DailyGameType.class,
                com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest.class);
        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);

        assertThat(transactional).isNull();
    }

    @Test
    @DisplayName("[giveUp] Should Require A Transaction - When The Give Up Method Is Inspected")
    void shouldRequireATransactionWhenTheGiveUpMethodIsInspected() throws NoSuchMethodException {
        Method method = DailyGameServiceImpl.class.getMethod("giveUp", UUID.class, DailyGameType.class);
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
                new com.watchwise.watchwise_api.dailygame.service.DailyGameAttemptDetailsCodec(),
                infoComparisonService,
                filmographyService,
                new com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor(),
                mediaService,
                CLOCK);
    }

    private DailyGameInfoFeedbackDTO infoFeedback() {
        DailyGameComparisonCellDTO match = new DailyGameComparisonCellDTO(
                DailyGameComparisonStatus.MATCH, null, "value", List.of(), null);
        return new DailyGameInfoFeedbackDTO(match, match, match, match, match, match, match, match);
    }

    private void stubOpenSubmission(DailyChallenge challenge, UserDailyGameResult result) {
        when(challengeRepository.findByChallengeDateAndGameType(TODAY, challenge.getGameType()))
                .thenReturn(Optional.of(challenge));
        org.mockito.Mockito.lenient().when(resultRepository.insertIfAbsent(
                any(), eq(USER_ID), eq(challenge.getId()), eq(NOW))).thenReturn(1);
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(USER_ID, List.of(challenge.getId())))
                .thenReturn(List.of(result));
        org.mockito.Mockito.lenient()
                .when(resultRepository.findByUserIdAndDailyChallengeIdForUpdate(USER_ID, challenge.getId()))
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
        return challenge(type, tmdbId, TODAY);
    }

    private DailyChallenge challenge(DailyGameType type, String tmdbId, LocalDate challengeDate) {
        if (type.targetKind() == DailyGameTargetKind.EPISODE) {
            return challenge(type, tmdbId, 1, 1);
        }
        UUID id = UUID.randomUUID();
        return DailyChallenge.builder()
                .id(id)
                .challengeDate(challengeDate)
                .gameType(type)
                .targetKind(type.targetKind())
                .targetTmdbId(tmdbId)
                .answerKey(type.targetKind().name() + ":" + tmdbId)
                .imagePath("/image.jpg")
                .answerSnapshot(Map.of(
                        "targetKind", type.targetKind().name(),
                        "tmdbId", tmdbId,
                        "title", "Answer",
                        "imageUrl", "/image.jpg"))
                .displaySnapshot(Map.of("imageUrl", "/image.jpg"))
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
                .answerSnapshot(Map.of(
                        "targetKind", "EPISODE",
                        "seriesTmdbId", seriesTmdbId,
                        "seasonNumber", seasonNumber,
                        "episodeNumber", episodeNumber,
                        "seriesName", "Breaking Bad",
                        "seriesPosterPath", "/poster.jpg",
                        "seriesYear", 2008,
                        "episodeName", "Episode",
                        "title", "Episode",
                        "imageUrl", "/still.jpg"))
                .displaySnapshot(Map.of("imagePaths", List.of(
                        "/still-1.jpg", "/still-2.jpg", "/still-3.jpg",
                        "/still-4.jpg", "/still-5.jpg", "/still-6.jpg")))
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

    private DailyGameAttemptRequest request(String tmdbId, String personTmdbId, String seriesTmdbId,
                                            Integer seasonNumber, Integer episodeNumber, Boolean shareOnCompletion) {
        return new DailyGameAttemptRequest(
                tmdbId, personTmdbId, seriesTmdbId, seasonNumber, episodeNumber, shareOnCompletion);
    }
}
