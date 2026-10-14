package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameRankingViewDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameRankingViewEntryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameRankingViewerDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.repository.UserDailyGameResultRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyGameRankingViewServiceImplTest {

    private static final UUID VIEWER_ID = UUID.randomUUID();
    private static final UUID ENTRY_ID = UUID.randomUUID();

    @Mock
    private UserDailyGameResultRepository resultRepository;

    @Mock
    private UserRepository userRepository;

    @Test
    @DisplayName("[getRankingView] Should Map General Ranking And Viewer Metrics - When The Viewer Is Outside The Page")
    void shouldMapGeneralRankingAndViewerMetricsWhenViewerIsOutsideThePage() {
        UserDailyGameResultRepository.DailyGameRankingViewProjection entryProjection = projection(
                1L, ENTRY_ID, "", "entry-user", "/entry.png", 30L, 2L, 3L, 2L,
                new BigDecimal("2.25"));
        UserDailyGameResultRepository.DailyGameRankingViewProjection viewerProjection = projection(
                250L, VIEWER_ID, "Viewer", "viewer-user", "/viewer.png", 20L, 5L, 3L, 1L,
                new BigDecimal("2.24"));
        when(resultRepository.findRankingViewByGameType(null, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(entryProjection), PageRequest.of(0, 20), 21));
        when(resultRepository.findRankingViewerByGameType(null, VIEWER_ID))
                .thenReturn(Optional.of(viewerProjection));
        when(resultRepository.findTop200CutoffScoreByGameType(null, 200))
                .thenReturn(Optional.of(25L));

        DailyGameRankingViewDTO result = service().getRankingView(VIEWER_ID, null, 1, 99);

        DailyGameRankingViewEntryDTO entry = result.content().getFirst();
        assertThat(result.page()).isOne();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.gameType()).isNull();
        assertThat(entry.name()).isEqualTo("entry-user");
        assertThat(entry.solvedPercentage()).isEqualTo(67);
        assertThat(entry.averageAttempts()).isEqualByComparingTo("2.3");

        DailyGameRankingViewerDTO viewer = result.viewer();
        assertThat(viewer.rank()).isEqualTo(250L);
        assertThat(viewer.score()).isEqualTo(20L);
        assertThat(viewer.solvedPercentage()).isEqualTo(33);
        assertThat(viewer.averageAttempts()).isEqualByComparingTo("2.2");
        assertThat(viewer.inTop200()).isFalse();
        assertThat(viewer.top200CutoffScore()).isEqualTo(25L);
        assertThat(viewer.pointsToTop200()).isEqualTo(6L);
        verify(resultRepository).findRankingViewerByGameType(null, VIEWER_ID);
        verify(resultRepository).findTop200CutoffScoreByGameType(null, 200);
        verify(userRepository, never()).findById(VIEWER_ID);
    }

    @Test
    @DisplayName("[getRankingView] Should Use Selected Game Type And Zero Viewer Metrics - When Viewer Has No Terminal Result")
    void shouldUseSelectedGameTypeAndZeroViewerMetricsWhenViewerHasNoTerminalResult() {
        User viewerUser = User.builder()
                .id(VIEWER_ID)
                .name(" ")
                .username("viewer-user")
                .profilePicture("/viewer.png")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(resultRepository.findRankingViewByGameType(
                DailyGameType.MOVIE_BY_INFO.name(), PageRequest.of(1, 5)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 200));
        when(resultRepository.findRankingViewerByGameType(DailyGameType.MOVIE_BY_INFO.name(), VIEWER_ID))
                .thenReturn(Optional.empty());
        when(userRepository.findById(VIEWER_ID)).thenReturn(Optional.of(viewerUser));
        when(resultRepository.findTop200CutoffScoreByGameType(DailyGameType.MOVIE_BY_INFO.name(), 200))
                .thenReturn(Optional.of(4L));

        DailyGameRankingViewDTO result = service().getRankingView(
                VIEWER_ID, DailyGameType.MOVIE_BY_INFO, 2, 5);

        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(5);
        assertThat(result.totalElements()).isEqualTo(200);
        assertThat(result.gameType()).isEqualTo(DailyGameType.MOVIE_BY_INFO);
        assertThat(result.viewer())
                .extracting(DailyGameRankingViewerDTO::name, DailyGameRankingViewerDTO::username,
                        DailyGameRankingViewerDTO::profilePicture, DailyGameRankingViewerDTO::rank,
                        DailyGameRankingViewerDTO::score, DailyGameRankingViewerDTO::daysPlayed,
                        DailyGameRankingViewerDTO::gamesPlayed, DailyGameRankingViewerDTO::solvedPercentage,
                        DailyGameRankingViewerDTO::averageAttempts, DailyGameRankingViewerDTO::inTop200,
                        DailyGameRankingViewerDTO::top200CutoffScore, DailyGameRankingViewerDTO::pointsToTop200)
                .containsExactly("viewer-user", "viewer-user", "/viewer.png", null, 0L, 0L, 0L, 0,
                        new BigDecimal("0.0"), false, 4L, 5L);
        verify(resultRepository).findRankingViewByGameType(
                DailyGameType.MOVIE_BY_INFO.name(), PageRequest.of(1, 5));
        verify(resultRepository).findRankingViewerByGameType(DailyGameType.MOVIE_BY_INFO.name(), VIEWER_ID);
        verify(resultRepository).findTop200CutoffScoreByGameType(DailyGameType.MOVIE_BY_INFO.name(), 200);
    }

    @Test
    @DisplayName("[getRankingView] Should Omit Cutoff - When Viewer Is In The Top Two Hundred")
    void shouldOmitCutoffWhenViewerIsInTopTwoHundred() {
        UserDailyGameResultRepository.DailyGameRankingViewProjection viewerProjection = projection(
                2L, VIEWER_ID, "Viewer", "viewer-user", "/viewer.png", 20L, 1L, 1L, 1L,
                new BigDecimal("1.0"));
        when(resultRepository.findRankingViewByGameType(null, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 1));
        when(resultRepository.findRankingViewerByGameType(null, VIEWER_ID))
                .thenReturn(Optional.of(viewerProjection));

        DailyGameRankingViewDTO result = service().getRankingView(VIEWER_ID, null, null, null);

        assertThat(result.viewer().inTop200()).isTrue();
        assertThat(result.viewer().top200CutoffScore()).isNull();
        assertThat(result.viewer().pointsToTop200()).isNull();
        verify(resultRepository, never()).findTop200CutoffScoreByGameType(null, 200);
    }

    @Test
    @DisplayName("[getRankingView] Should Keep Cutoff Null - When Ranked Population Is Smaller Than Two Hundred")
    void shouldKeepCutoffNullWhenRankedPopulationIsSmallerThanTwoHundred() {
        UserDailyGameResultRepository.DailyGameRankingViewProjection viewerProjection = projection(
                201L, VIEWER_ID, "Viewer", "viewer-user", "/viewer.png", 20L, 1L, 1L, 1L,
                new BigDecimal("1.0"));
        when(resultRepository.findRankingViewByGameType(null, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 2));
        when(resultRepository.findRankingViewerByGameType(null, VIEWER_ID))
                .thenReturn(Optional.of(viewerProjection));
        when(resultRepository.findTop200CutoffScoreByGameType(null, 200))
                .thenReturn(Optional.empty());

        DailyGameRankingViewDTO result = service().getRankingView(VIEWER_ID, null, null, null);

        assertThat(result.viewer().top200CutoffScore()).isNull();
        assertThat(result.viewer().pointsToTop200()).isNull();
    }

    private DailyGameRankingViewServiceImpl service() {
        return new DailyGameRankingViewServiceImpl(
                resultRepository, userRepository, new PageRequestFactory());
    }

    private UserDailyGameResultRepository.DailyGameRankingViewProjection projection(
            Long rank, UUID userId, String name, String username, String profilePicture,
            Long score, Long daysPlayed, Long gamesPlayed, Long solvedGames, BigDecimal averageAttempts) {
        UserDailyGameResultRepository.DailyGameRankingViewProjection projection =
                mock(UserDailyGameResultRepository.DailyGameRankingViewProjection.class);
        when(projection.getRank()).thenReturn(rank);
        when(projection.getUserId()).thenReturn(userId);
        when(projection.getName()).thenReturn(name);
        when(projection.getUsername()).thenReturn(username);
        when(projection.getProfilePicture()).thenReturn(profilePicture);
        when(projection.getScore()).thenReturn(score);
        when(projection.getDaysPlayed()).thenReturn(daysPlayed);
        when(projection.getGamesPlayed()).thenReturn(gamesPlayed);
        when(projection.getSolvedGames()).thenReturn(solvedGames);
        when(projection.getAverageAttempts()).thenReturn(averageAttempts);
        return projection;
    }
}
