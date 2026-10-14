package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameRankingViewDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameRankingViewEntryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameRankingViewerDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.repository.UserDailyGameResultRepository;
import com.watchwise.watchwise_api.dailygame.service.DailyGameRankingViewService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
public class DailyGameRankingViewServiceImpl implements DailyGameRankingViewService {

    private static final int MAX_PAGE_SIZE = 20;
    private static final int TOP_200_LIMIT = 200;
    private static final BigDecimal ZERO_AVERAGE_ATTEMPTS = BigDecimal.ZERO.setScale(1);

    private final UserDailyGameResultRepository resultRepository;
    private final UserRepository userRepository;
    private final PageRequestFactory pageRequestFactory;

    public DailyGameRankingViewServiceImpl(
            UserDailyGameResultRepository resultRepository,
            UserRepository userRepository,
            PageRequestFactory pageRequestFactory) {
        this.resultRepository = resultRepository;
        this.userRepository = userRepository;
        this.pageRequestFactory = pageRequestFactory;
    }

    @Override
    public DailyGameRankingViewDTO getRankingView(
            UUID viewerId, DailyGameType gameType, Integer page, Integer size) {
        Pageable pageable = pageRequestFactory.build(page, size, MAX_PAGE_SIZE);
        String gameTypeName = gameType == null ? null : gameType.name();
        Page<UserDailyGameResultRepository.DailyGameRankingViewProjection> rankingPage =
                resultRepository.findRankingViewByGameType(gameTypeName, pageable);
        UserDailyGameResultRepository.DailyGameRankingViewProjection viewerProjection =
                resultRepository.findRankingViewerByGameType(gameTypeName, viewerId).orElse(null);

        DailyGameRankingViewerDTO viewer = viewerProjection == null
                ? buildUnrankedViewer(viewerId, gameTypeName)
                : buildViewer(viewerProjection, gameTypeName);

        return new DailyGameRankingViewDTO(
                rankingPage.map(this::toEntry).getContent(),
                rankingPage.getNumber() + 1,
                rankingPage.getSize(),
                rankingPage.getTotalElements(),
                rankingPage.getTotalPages(),
                rankingPage.hasNext(),
                gameType,
                viewer);
    }

    private DailyGameRankingViewEntryDTO toEntry(
            UserDailyGameResultRepository.DailyGameRankingViewProjection projection) {
        return new DailyGameRankingViewEntryDTO(
                valueOrZero(projection.getRank()),
                projection.getUserId(),
                displayName(projection.getName(), projection.getUsername()),
                projection.getUsername(),
                projection.getProfilePicture(),
                valueOrZero(projection.getScore()),
                valueOrZero(projection.getDaysPlayed()),
                valueOrZero(projection.getGamesPlayed()),
                solvedPercentage(projection),
                averageAttempts(projection.getAverageAttempts()));
    }

    private DailyGameRankingViewerDTO buildViewer(
            UserDailyGameResultRepository.DailyGameRankingViewProjection projection,
            String gameTypeName) {
        long score = valueOrZero(projection.getScore());
        Long rank = projection.getRank();
        boolean inTop200 = rank != null && rank <= TOP_200_LIMIT;
        Long cutoffScore = null;
        Long pointsToTop200 = null;
        if (!inTop200) {
            cutoffScore = resultRepository.findTop200CutoffScoreByGameType(gameTypeName, TOP_200_LIMIT)
                    .orElse(null);
            if (cutoffScore != null) {
                pointsToTop200 = cutoffScore - score + 1;
            }
        }

        return new DailyGameRankingViewerDTO(
                projection.getUserId(),
                displayName(projection.getName(), projection.getUsername()),
                projection.getUsername(),
                projection.getProfilePicture(),
                rank,
                score,
                valueOrZero(projection.getDaysPlayed()),
                valueOrZero(projection.getGamesPlayed()),
                solvedPercentage(projection),
                averageAttempts(projection.getAverageAttempts()),
                inTop200,
                cutoffScore,
                pointsToTop200);
    }

    private DailyGameRankingViewerDTO buildUnrankedViewer(UUID viewerId, String gameTypeName) {
        User user = userRepository.findById(viewerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        Long cutoffScore = resultRepository.findTop200CutoffScoreByGameType(gameTypeName, TOP_200_LIMIT)
                .orElse(null);
        Long pointsToTop200 = cutoffScore == null ? null : cutoffScore + 1;

        return new DailyGameRankingViewerDTO(
                user.getId(),
                displayName(user.getName(), user.getUsername()),
                user.getUsername(),
                user.getProfilePicture(),
                null,
                0,
                0,
                0,
                0,
                ZERO_AVERAGE_ATTEMPTS,
                false,
                cutoffScore,
                pointsToTop200);
    }

    private int solvedPercentage(
            UserDailyGameResultRepository.DailyGameRankingViewProjection projection) {
        long gamesPlayed = valueOrZero(projection.getGamesPlayed());
        if (gamesPlayed == 0) {
            return 0;
        }
        return (int) Math.round(100.0 * valueOrZero(projection.getSolvedGames()) / gamesPlayed);
    }

    private BigDecimal averageAttempts(BigDecimal averageAttempts) {
        return averageAttempts == null
                ? ZERO_AVERAGE_ATTEMPTS
                : averageAttempts.setScale(1, RoundingMode.HALF_UP);
    }

    private String displayName(String name, String username) {
        return name == null || name.isBlank() ? username : name;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0 : value;
    }
}
