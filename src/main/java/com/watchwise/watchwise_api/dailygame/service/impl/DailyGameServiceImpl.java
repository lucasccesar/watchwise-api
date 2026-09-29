package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.DailyGamesUnavailableException;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameCandidateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameGuessFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameInfoFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameTodayResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameStateDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallengeHint;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameResultStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeHintRepository;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeRepository;
import com.watchwise.watchwise_api.dailygame.repository.UserDailyGameResultRepository;
import com.watchwise.watchwise_api.dailygame.service.DailyGameCandidateIdentity;
import com.watchwise.watchwise_api.dailygame.service.DailyGameCandidateValidator;
import com.watchwise.watchwise_api.dailygame.service.DailyGameAttemptDetailsCodec;
import com.watchwise.watchwise_api.dailygame.service.DailyGameService;
import com.watchwise.watchwise_api.dailygame.service.DailyGameInfoComparisonService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DailyGameServiceImpl implements DailyGameService {

    private final DailyChallengeRepository challengeRepository;
    private final DailyChallengeHintRepository hintRepository;
    private final UserDailyGameResultRepository resultRepository;
    private final DailyGameCandidateValidator candidateValidator;
    private final DailyChallengeResponseAssembler responseAssembler;
    private final DailyGameAttemptDetailsCodec attemptDetailsCodec;
    private final DailyGameInfoComparisonService infoComparisonService;
    private final Clock clock;

    public DailyGameServiceImpl(
            DailyChallengeRepository challengeRepository,
            DailyChallengeHintRepository hintRepository,
            UserDailyGameResultRepository resultRepository,
            DailyGameCandidateValidator candidateValidator,
            DailyChallengeResponseAssembler responseAssembler,
            Clock clock) {
        this(challengeRepository, hintRepository, resultRepository, candidateValidator,
                responseAssembler, new DailyGameAttemptDetailsCodec(), null, clock);
    }

    public DailyGameServiceImpl(
            DailyChallengeRepository challengeRepository,
            DailyChallengeHintRepository hintRepository,
            UserDailyGameResultRepository resultRepository,
            DailyGameCandidateValidator candidateValidator,
            DailyChallengeResponseAssembler responseAssembler,
            DailyGameInfoComparisonService infoComparisonService,
            Clock clock) {
        this(challengeRepository, hintRepository, resultRepository, candidateValidator,
                responseAssembler, new DailyGameAttemptDetailsCodec(), infoComparisonService, clock);
    }

    @Autowired
    public DailyGameServiceImpl(
            DailyChallengeRepository challengeRepository,
            DailyChallengeHintRepository hintRepository,
            UserDailyGameResultRepository resultRepository,
            DailyGameCandidateValidator candidateValidator,
            DailyChallengeResponseAssembler responseAssembler,
            DailyGameAttemptDetailsCodec attemptDetailsCodec,
            DailyGameInfoComparisonService infoComparisonService,
            Clock clock) {
        this.challengeRepository = challengeRepository;
        this.hintRepository = hintRepository;
        this.resultRepository = resultRepository;
        this.candidateValidator = candidateValidator;
        this.responseAssembler = responseAssembler;
        this.attemptDetailsCodec = attemptDetailsCodec;
        this.infoComparisonService = infoComparisonService;
        this.clock = clock;
    }

    @Override
    public DailyGameTodayResponseDTO getToday(UUID userId) {
        return getDay(userId, LocalDate.now(clock));
    }

    @Override
    public DailyGameTodayResponseDTO getDay(UUID userId, LocalDate challengeDate) {
        assertAvailableDate(challengeDate);
        List<DailyChallenge> challenges = challengeRepository.findByChallengeDateOrderByGameTypeAsc(challengeDate);
        requireCompleteDailySet(challenges);

        List<UUID> challengeIds = challenges.stream().map(DailyChallenge::getId).toList();
        Map<UUID, UserDailyGameResult> results = resultRepository
                .findByUserIdAndDailyChallengeIdIn(userId, challengeIds)
                .stream()
                .collect(Collectors.toMap(result -> result.getDailyChallenge().getId(), Function.identity()));
        Map<UUID, List<DailyChallengeHint>> hints = hintRepository
                .findByDailyChallengeIdInOrderByDailyChallengeIdAscPositionAsc(challengeIds)
                .stream()
                .collect(Collectors.groupingBy(hint -> hint.getDailyChallenge().getId()));

        return responseAssembler.toTodayResponse(challengeDate, LocalDate.now(clock), challenges, results, hints);
    }

    @Override
    public DailyGameStateDTO getGame(
            UUID userId, LocalDate challengeDate, DailyGameType gameType, boolean majorRoles) {
        requireGameType(gameType);
        assertAvailableDate(challengeDate);
        DailyChallenge challenge = challengeRepository.findByChallengeDateAndGameType(challengeDate, gameType)
                .orElseThrow(DailyGamesUnavailableException::new);
        UserDailyGameResult result = resultRepository
                .findByUserIdAndDailyChallengeIdIn(userId, List.of(challenge.getId()))
                .stream()
                .findFirst()
                .orElse(null);
        List<DailyChallengeHint> hints = hintRepository.findByDailyChallengeIdOrderByPositionAsc(challenge.getId());
        return responseAssembler.toState(
                challenge, result, hints, challengeDate.equals(LocalDate.now(clock)));
    }

    @Override
    @Transactional
    public DailyGameAttemptResponseDTO submitAttempt(
            UUID userId, DailyGameType gameType, DailyGameAttemptRequest request) {
        return submitAttempt(userId, LocalDate.now(clock), gameType, request);
    }

    @Override
    @Transactional
    public DailyGameAttemptResponseDTO submitAttempt(
            UUID userId, LocalDate challengeDate, DailyGameType gameType, DailyGameAttemptRequest request) {
        requireGameType(gameType);
        assertAvailableDate(challengeDate);
        LocalDateTime now = LocalDateTime.now(clock);
        DailyChallenge challenge = challengeRepository.findByChallengeDateAndGameType(challengeDate, gameType)
                .orElseThrow(DailyGamesUnavailableException::new);

        resultRepository.insertIfAbsent(UUID.randomUUID(), userId, challenge.getId(), now);
        UserDailyGameResult result = resultRepository
                .findByUserIdAndDailyChallengeIdForUpdate(userId, challenge.getId())
                .orElseThrow(() -> new IllegalStateException("Daily game result was not created"));
        assertOpenAndHasAttempts(result, gameType);

        DailyGameCandidateIdentity candidate = candidateValidator.validate(gameType, request);
        DailyGameGuessFeedbackDTO episodeFeedback = guessFeedback(gameType, challenge, candidate);
        DailyGameInfoFeedbackDTO infoFeedback = infoFeedback(gameType, challenge, candidate);
        int attemptNumber = result.getAttemptsUsed() + 1;
        result.setAttemptsUsed(attemptNumber);
        result.setUpdatedAt(now);
        if (challenge.getAnswerKey().equals(candidate.answerKey())) {
            result.setScore(gameType.maxAttempts() - attemptNumber + 1);
            result.setStatus(DailyGameResultStatus.COMPLETED);
            result.setCompletedAt(now);
        } else if (attemptNumber >= gameType.maxAttempts()) {
            result.setScore(0);
            result.setStatus(DailyGameResultStatus.FAILED);
            result.setCompletedAt(now);
        } else {
            result.setScore(0);
            result.setStatus(DailyGameResultStatus.IN_PROGRESS);
            result.setCompletedAt(null);
        }
        appendAttemptDetails(challenge, result, attemptNumber, candidate, episodeFeedback, infoFeedback);
        List<DailyChallengeHint> hints = hintRepository.findByDailyChallengeIdOrderByPositionAsc(challenge.getId());
        return responseAssembler.toAttemptResponse(challenge, result, hints,
                episodeFeedback, challengeDate.equals(LocalDate.now(clock)));
    }

    @Override
    @Transactional
    public DailyGameAttemptResponseDTO giveUp(UUID userId, DailyGameType gameType) {
        return giveUp(userId, LocalDate.now(clock), gameType);
    }

    @Override
    @Transactional
    public DailyGameAttemptResponseDTO giveUp(UUID userId, LocalDate challengeDate, DailyGameType gameType) {
        requireGameType(gameType);
        assertAvailableDate(challengeDate);
        LocalDateTime now = LocalDateTime.now(clock);
        DailyChallenge challenge = challengeRepository.findByChallengeDateAndGameType(challengeDate, gameType)
                .orElseThrow(DailyGamesUnavailableException::new);
        resultRepository.insertIfAbsent(UUID.randomUUID(), userId, challenge.getId(), now);
        UserDailyGameResult result = resultRepository
                .findByUserIdAndDailyChallengeIdForUpdate(userId, challenge.getId())
                .orElseThrow(() -> new IllegalStateException("Daily game result was not created"));
        assertOpenAndHasAttempts(result, gameType);
        result.setStatus(DailyGameResultStatus.FAILED);
        result.setScore(0);
        result.setCompletedAt(now);
        result.setUpdatedAt(now);
        List<DailyChallengeHint> hints = hintRepository.findByDailyChallengeIdOrderByPositionAsc(challenge.getId());
        return responseAssembler.toAttemptResponse(
                challenge, result, hints, null, challengeDate.equals(LocalDate.now(clock)));
    }

    private void appendAttemptDetails(
            DailyChallenge challenge,
            UserDailyGameResult result,
            int attemptNumber,
            DailyGameCandidateIdentity candidate,
            DailyGameGuessFeedbackDTO episodeFeedback,
            DailyGameInfoFeedbackDTO infoFeedback) {
        if (!challenge.getChallengeDate().equals(LocalDate.now(clock))) {
            return;
        }
        DailyGameCandidateDTO candidateDto = new DailyGameCandidateDTO(
                candidate.targetKind(), candidate.tmdbId(), candidate.personTmdbId(), candidate.seriesTmdbId(),
                candidate.seasonNumber(), candidate.episodeNumber(), null, null, null);
        result.setAttemptDetails(attemptDetailsCodec.append(
                result.getAttemptDetails(),
                new DailyGameAttemptDTO(attemptNumber, candidateDto, episodeFeedback, infoFeedback, null)));
    }

    private void requireGameType(DailyGameType gameType) {
        if (gameType == null) {
            throw new BadRequestException("A daily game type is required");
        }
    }

    private DailyGameGuessFeedbackDTO guessFeedback(
            DailyGameType gameType, DailyChallenge challenge, DailyGameCandidateIdentity candidate) {
        if (gameType != DailyGameType.EPISODE_BY_FRAME) {
            return null;
        }
        boolean seriesCorrect = Objects.equals(challenge.getSeriesTmdbId(), candidate.seriesTmdbId());
        boolean seasonCorrect = Objects.equals(challenge.getSeasonNumber(), candidate.seasonNumber());
        boolean episodeCorrect = Objects.equals(challenge.getEpisodeNumber(), candidate.episodeNumber());
        return new DailyGameGuessFeedbackDTO(
                seriesCorrect, seasonCorrect, episodeCorrect,
                seriesCorrect && seasonCorrect && episodeCorrect);
    }

    private DailyGameInfoFeedbackDTO infoFeedback(
            DailyGameType gameType, DailyChallenge challenge, DailyGameCandidateIdentity candidate) {
        if (infoComparisonService == null
                || (gameType != DailyGameType.MOVIE_BY_INFO && gameType != DailyGameType.SERIES_BY_INFO)) {
            return null;
        }
        return infoComparisonService.compare(challenge, candidate);
    }

    private void assertAvailableDate(LocalDate challengeDate) {
        if (challengeDate == null || challengeDate.isAfter(LocalDate.now(clock))) {
            throw new BadRequestException("Daily games are not available for a future date");
        }
    }

    private void requireCompleteDailySet(List<DailyChallenge> challenges) {
        if (challenges == null || challenges.size() != DailyGameType.values().length) {
            throw new DailyGamesUnavailableException();
        }
        EnumSet<DailyGameType> types = EnumSet.noneOf(DailyGameType.class);
        for (DailyChallenge challenge : challenges) {
            if (challenge == null || challenge.getGameType() == null) {
                throw new DailyGamesUnavailableException();
            }
            types.add(challenge.getGameType());
        }
        if (!types.equals(EnumSet.allOf(DailyGameType.class))) {
            throw new DailyGamesUnavailableException();
        }
    }

    private void assertOpenAndHasAttempts(UserDailyGameResult result, DailyGameType gameType) {
        if (result.getStatus() != DailyGameResultStatus.IN_PROGRESS
                || result.getAttemptsUsed() >= gameType.maxAttempts()) {
            throw new ConflictException("Daily game attempt is already finished");
        }
    }
}
