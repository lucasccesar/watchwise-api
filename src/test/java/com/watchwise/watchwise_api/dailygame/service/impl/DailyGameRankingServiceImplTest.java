package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameHistoryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameRankingEntryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameResultStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeRepository;
import com.watchwise.watchwise_api.dailygame.repository.UserDailyGameResultRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyGameRankingServiceImplTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 27);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 12, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private DailyChallengeRepository challengeRepository;

    @Mock
    private UserDailyGameResultRepository resultRepository;

    @Test
    @DisplayName("[getHistory] Should Return Empty History - When No Past Challenges Exist")
    void shouldReturnEmptyHistoryWhenNoPastChallengesExist() {
        when(challengeRepository.findByChallengeDateBeforeOrderByChallengeDateDescGameTypeAscIdAsc(
                any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(service().getHistory(USER_ID, null, null, null).getContent()).isEmpty();
        verifyNoInteractions(resultRepository);
    }

    @Test
    @DisplayName("[DailyGameHistoryDTO] Should Match The Locked Contract - When The Record Is Inspected")
    void shouldMatchTheLockedHistoryContractWhenTheRecordIsInspected() {
        assertThat(Arrays.stream(DailyGameHistoryDTO.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList())
                .containsExactly(
                        "challengeDate", "gameType", "targetKind", "maxAttempts", "status",
                        "attemptsUsed", "score", "completedAt", "answer");
    }

    @Test
    @DisplayName("[DailyGameRankingEntryDTO] Should Match The Locked Contract - When The Record Is Inspected")
    void shouldMatchTheLockedRankingContractWhenTheRecordIsInspected() {
        assertThat(Arrays.stream(DailyGameRankingEntryDTO.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList())
                .containsExactly("rank", "userId", "username", "profilePicture", "score", "gamesPlayed");
    }

    @Test
    @DisplayName("[DailyGameRankingProjection] Should Expose The Locked Aggregate Names - When The Interface Is Inspected")
    void shouldExposeTheLockedAggregateNamesWhenTheInterfaceIsInspected() {
        assertThat(Arrays.stream(UserDailyGameResultRepository.DailyGameRankingProjection.class.getDeclaredMethods())
                .map(method -> method.getName())
                .toList())
                .contains("getScore", "getGamesPlayed")
                .doesNotContain("getTotalScore", "getTotalAttempts");
    }

    @Test
    @DisplayName("[getHistory] Should Return NotPlayed History With Revealed Answer - When The User Has No Result")
    void shouldReturnNotPlayedHistoryWithRevealedAnswerWhenTheUserHasNoResult() {
        DailyChallenge challenge = challenge(TODAY.minusDays(1), DailyGameType.MOVIE_BY_POSTER);
        when(challengeRepository.findByChallengeDateBeforeOrderByChallengeDateDescGameTypeAscIdAsc(
                eq(TODAY), any())).thenReturn(new PageImpl<>(List.of(challenge), PageRequest.of(0, 20), 1));
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(USER_ID, List.of(challenge.getId())))
                .thenReturn(List.of());

        DailyGameHistoryDTO history = service().getHistory(USER_ID, null, 1, 20).getContent().getFirst();

        assertThat(history.challengeDate()).isEqualTo(TODAY.minusDays(1));
        assertThat(history.status()).isEqualTo(DailyGameViewStatus.NOT_PLAYED);
        assertThat(history.attemptsUsed()).isZero();
        assertThat(history.score()).isZero();
        assertThat(history.completedAt()).isNull();
        assertThat(history.answer()).isNotNull();
        assertThat(history.answer().tmdbId()).isEqualTo("550");
        assertThat(history.answer().title()).isEqualTo("Frozen answer");
    }

    @Test
    @DisplayName("[getHistory] Should Exclude The Current Date - When Today Is Requested")
    void shouldExcludeCurrentDateFromHistoryWhenTodayIsRequested() {
        when(challengeRepository.findByChallengeDateBeforeOrderByChallengeDateDescGameTypeAscIdAsc(
                eq(TODAY), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service().getHistory(USER_ID, null, null, null);

        verify(challengeRepository).findByChallengeDateBeforeOrderByChallengeDateDescGameTypeAscIdAsc(
                eq(TODAY), any());
        verify(challengeRepository, never())
                .findByChallengeDateBeforeAndGameTypeOrderByChallengeDateDescGameTypeAscIdAsc(
                        any(), any(), any());
    }

    @Test
    @DisplayName("[getHistory] Should Filter By Type - When A Type Is Provided")
    void shouldFilterHistoryByTypeWhenATypeIsProvided() {
        DailyGameType type = DailyGameType.EPISODE_BY_FRAME;
        when(challengeRepository.findByChallengeDateBeforeAndGameTypeOrderByChallengeDateDescGameTypeAscIdAsc(
                eq(TODAY), eq(type), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(service().getHistory(USER_ID, type, null, null).getContent()).isEmpty();

        verify(challengeRepository).findByChallengeDateBeforeAndGameTypeOrderByChallengeDateDescGameTypeAscIdAsc(
                eq(TODAY), eq(type), any());
        verify(challengeRepository, never())
                .findByChallengeDateBeforeOrderByChallengeDateDescGameTypeAscIdAsc(any(), any());
    }

    @Test
    @DisplayName("[getHistory] Should Batch Results And Map Final States - When Results Exist")
    void shouldBatchResultsAndMapCompletedAndFailedHistoryWhenResultsExist() {
        DailyChallenge completedChallenge = challenge(TODAY.minusDays(1), DailyGameType.MOVIE_BY_POSTER);
        DailyChallenge failedChallenge = challenge(TODAY.minusDays(2), DailyGameType.SERIES_BY_INFO);
        List<DailyChallenge> challenges = List.of(completedChallenge, failedChallenge);
        when(challengeRepository.findByChallengeDateBeforeOrderByChallengeDateDescGameTypeAscIdAsc(
                eq(TODAY), any())).thenReturn(new PageImpl<>(challenges, PageRequest.of(0, 20), 2));
        when(resultRepository.findByUserIdAndDailyChallengeIdIn(
                USER_ID, List.of(completedChallenge.getId(), failedChallenge.getId())))
                .thenReturn(List.of(
                        result(completedChallenge, 2, 4, DailyGameResultStatus.COMPLETED),
                        result(failedChallenge, 10, 0, DailyGameResultStatus.FAILED)));

        List<DailyGameHistoryDTO> history = service().getHistory(USER_ID, null, null, null).getContent();

        assertThat(history).extracting(DailyGameHistoryDTO::status)
                .containsExactly(DailyGameViewStatus.COMPLETED, DailyGameViewStatus.FAILED);
        assertThat(history).extracting(DailyGameHistoryDTO::score).containsExactly(4, 0);
        assertThat(history).allSatisfy(entry -> assertThat(entry.answer()).isNotNull());
        verify(resultRepository).findByUserIdAndDailyChallengeIdIn(
                USER_ID, List.of(completedChallenge.getId(), failedChallenge.getId()));
        verifyNoMoreInteractions(resultRepository);
    }

    @Test
    @DisplayName("[getHistory] Should Clamp Page Size To One Hundred - When Size Exceeds The Limit")
    void shouldUsePageRequestFactoryMaximumWhenHistorySizeExceedsTheLimit() {
        when(challengeRepository.findByChallengeDateBeforeOrderByChallengeDateDescGameTypeAscIdAsc(
                eq(TODAY), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        service().getHistory(USER_ID, null, 2, 101);

        verify(challengeRepository).findByChallengeDateBeforeOrderByChallengeDateDescGameTypeAscIdAsc(
                eq(TODAY), eq(PageRequest.of(1, 100)));
    }

    @Test
    @DisplayName("[getHistory] Should Reject The Page Size - When Size Is Not Positive")
    void shouldRejectInvalidHistoryPageSizeWhenSizeIsNotPositive() {
        assertThatThrownBy(() -> service().getHistory(USER_ID, null, 1, 0))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(challengeRepository, resultRepository);
    }

    @Test
    @DisplayName("[getGeneralRanking] Should Map Aggregate Fields - When Final Results Are Returned")
    void shouldAggregateGeneralRankingWhenFinalResultsAreReturned() {
        UserDailyGameResultRepository.DailyGameRankingProjection projection = rankingProjection(
                1L, USER_ID, "lucas", "lucas.png", 18L, 7L);
        when(resultRepository.findRankingByGameType(null, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(projection), PageRequest.of(0, 20), 1));

        DailyGameRankingEntryDTO entry = service().getGeneralRanking(null, null).getContent().getFirst();

        assertThat(entry.rank()).isEqualTo(1L);
        assertThat(entry.userId()).isEqualTo(USER_ID);
        assertThat(entry.username()).isEqualTo("lucas");
        assertThat(entry.profilePicture()).isEqualTo("lucas.png");
        assertThat(entry.score()).isEqualTo(18L);
        assertThat(entry.gamesPlayed()).isEqualTo(7L);
        verify(resultRepository).findRankingByGameType(null, PageRequest.of(0, 20));
    }

    @ParameterizedTest
    @EnumSource(DailyGameType.class)
    @DisplayName("[getRanking] Should Filter By Each Daily Game Type - When A Type Is Provided")
    void shouldFilterRankingByEachDailyGameTypeWhenATypeIsProvided(DailyGameType type) {
        when(resultRepository.findRankingByGameType(eq(type.name()), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(service().getRanking(type, null, null).getContent()).isEmpty();

        verify(resultRepository).findRankingByGameType(eq(type.name()), eq(PageRequest.of(0, 20)));
    }

    @Test
    @DisplayName("[getGeneralRanking] Should Return Empty Ranking - When No Final Results Exist")
    void shouldReturnEmptyRankingWhenNoFinalResultsExist() {
        when(resultRepository.findRankingByGameType(null, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(service().getGeneralRanking(null, null).getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getGeneralRanking] Should Reject The Page Size - When Size Is Not Positive")
    void shouldRejectInvalidRankingPageSizeWhenSizeIsNotPositive() {
        assertThatThrownBy(() -> service().getGeneralRanking(1, 0))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(resultRepository);
    }

    private DailyGameRankingServiceImpl service() {
        return new DailyGameRankingServiceImpl(
                challengeRepository,
                resultRepository,
                new DailyChallengeResponseAssembler(),
                new com.watchwise.watchwise_api.common.pagination.PageRequestFactory(),
                CLOCK);
    }

    private DailyChallenge challenge(LocalDate date, DailyGameType type) {
        boolean episode = type.targetKind() == DailyGameTargetKind.EPISODE;
        return DailyChallenge.builder()
                .id(UUID.randomUUID())
                .challengeDate(date)
                .gameType(type)
                .targetKind(type.targetKind())
                .targetTmdbId(episode ? null : "550")
                .seriesTmdbId(episode ? "1396" : null)
                .seasonNumber(episode ? 1 : null)
                .episodeNumber(episode ? 1 : null)
                .answerKey(type.name() + ":answer")
                .imagePath("https://example.com/image.jpg")
                .answerSnapshot(Map.of("title", "Frozen answer"))
                .displaySnapshot(Map.of())
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private UserDailyGameResult result(
            DailyChallenge challenge, int attempts, int score, DailyGameResultStatus status) {
        return UserDailyGameResult.builder()
                .id(UUID.randomUUID())
                .dailyChallenge(challenge)
                .attemptsUsed(attempts)
                .score(score)
                .status(status)
                .completedAt(NOW)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private UserDailyGameResultRepository.DailyGameRankingProjection rankingProjection(
            Long rank, UUID userId, String username, String profilePicture,
            Long score, Long gamesPlayed) {
        UserDailyGameResultRepository.DailyGameRankingProjection projection =
                mock(UserDailyGameResultRepository.DailyGameRankingProjection.class);
        when(projection.getRank()).thenReturn(rank);
        when(projection.getUserId()).thenReturn(userId);
        when(projection.getUsername()).thenReturn(username);
        when(projection.getProfilePicture()).thenReturn(profilePicture);
        when(projection.getScore()).thenReturn(score);
        when(projection.getGamesPlayed()).thenReturn(gamesPlayed);
        return projection;
    }
}
