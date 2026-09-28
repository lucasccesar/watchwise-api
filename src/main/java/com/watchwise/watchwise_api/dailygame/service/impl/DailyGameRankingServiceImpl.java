package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameHistoryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameRankingEntryDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeRepository;
import com.watchwise.watchwise_api.dailygame.repository.UserDailyGameResultRepository;
import com.watchwise.watchwise_api.dailygame.service.DailyGameRankingService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DailyGameRankingServiceImpl implements DailyGameRankingService {

    private static final int MAX_PAGE_SIZE = 100;

    private final DailyChallengeRepository challengeRepository;
    private final UserDailyGameResultRepository resultRepository;
    private final DailyChallengeResponseAssembler responseAssembler;
    private final PageRequestFactory pageRequestFactory;
    private final Clock clock;

    public DailyGameRankingServiceImpl(
            DailyChallengeRepository challengeRepository,
            UserDailyGameResultRepository resultRepository,
            DailyChallengeResponseAssembler responseAssembler,
            PageRequestFactory pageRequestFactory,
            Clock clock) {
        this.challengeRepository = challengeRepository;
        this.resultRepository = resultRepository;
        this.responseAssembler = responseAssembler;
        this.pageRequestFactory = pageRequestFactory;
        this.clock = clock;
    }

    @Override
    public Page<DailyGameHistoryDTO> getHistory(UUID userId, DailyGameType type, Integer page, Integer size) {
        Pageable pageable = pageRequestFactory.build(page, size, MAX_PAGE_SIZE);
        LocalDate today = LocalDate.now(clock);
        Page<DailyChallenge> challenges = type == null
                ? challengeRepository.findByChallengeDateBeforeOrderByChallengeDateDescGameTypeAscIdAsc(
                        today, pageable)
                : challengeRepository.findByChallengeDateBeforeAndGameTypeOrderByChallengeDateDescGameTypeAscIdAsc(
                        today, type, pageable);

        if (challenges.isEmpty()) {
            return new PageImpl<>(List.of(), challenges.getPageable(), challenges.getTotalElements());
        }

        List<UUID> challengeIds = challenges.getContent().stream().map(DailyChallenge::getId).toList();
        Map<UUID, UserDailyGameResult> resultsByChallengeId = resultRepository
                .findByUserIdAndDailyChallengeIdIn(userId, challengeIds)
                .stream()
                .collect(Collectors.toMap(
                        result -> result.getDailyChallenge().getId(), Function.identity()));

        return challenges.map(challenge -> responseAssembler.toHistoryResponse(
                challenge.getChallengeDate(), challenge, resultsByChallengeId.get(challenge.getId())));
    }

    @Override
    public Page<DailyGameRankingEntryDTO> getGeneralRanking(Integer page, Integer size) {
        return getRanking(null, page, size);
    }

    @Override
    public Page<DailyGameRankingEntryDTO> getRanking(DailyGameType type, Integer page, Integer size) {
        Pageable pageable = pageRequestFactory.build(page, size, MAX_PAGE_SIZE);
        String gameType = type == null ? null : type.name();
        return resultRepository.findRankingByGameType(gameType, pageable)
                .map(projection -> new DailyGameRankingEntryDTO(
                        projection.getRank(), projection.getUserId(), projection.getUsername(),
                        projection.getProfilePicture(), projection.getScore(), projection.getAttemptsUsed()));
    }
}
