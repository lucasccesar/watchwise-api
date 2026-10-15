package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.DailyGamesUnavailableException;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameCandidateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameGuessFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameInfoFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameOverviewCardDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameTodayResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameStateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGamesOverviewDTO;
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
import com.watchwise.watchwise_api.dailygame.service.DailyGameFilmographyService;
import com.watchwise.watchwise_api.dailygame.service.DailyGameMediaService;
import com.watchwise.watchwise_api.dailygame.media.DailyGameImageVariant;
import com.watchwise.watchwise_api.dailygame.media.DailyGameMediaSlot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
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
    private final DailyGameFilmographyService filmographyService;
    private final NewTransactionExecutor newTransactionExecutor;
    private final Clock clock;
    private final DailyGameMediaService mediaService;

    @Autowired
    public DailyGameServiceImpl(
            DailyChallengeRepository challengeRepository,
            DailyChallengeHintRepository hintRepository,
            UserDailyGameResultRepository resultRepository,
            DailyGameCandidateValidator candidateValidator,
            DailyChallengeResponseAssembler responseAssembler,
            DailyGameAttemptDetailsCodec attemptDetailsCodec,
            DailyGameInfoComparisonService infoComparisonService,
            DailyGameFilmographyService filmographyService,
            NewTransactionExecutor newTransactionExecutor,
            DailyGameMediaService mediaService,
            Clock clock) {
        this.challengeRepository = challengeRepository;
        this.hintRepository = hintRepository;
        this.resultRepository = resultRepository;
        this.candidateValidator = candidateValidator;
        this.responseAssembler = responseAssembler;
        this.attemptDetailsCodec = attemptDetailsCodec;
        this.infoComparisonService = infoComparisonService;
        this.filmographyService = filmographyService;
        this.newTransactionExecutor = newTransactionExecutor;
        this.mediaService = Objects.requireNonNull(mediaService);
        this.clock = clock;
    }

    @Override
    public DailyGameTodayResponseDTO getToday(UUID userId) {
        return getDay(userId, LocalDate.now(clock));
    }

    @Override
    public DailyGamesOverviewDTO getTodayOverview(UUID userId) {
        LocalDate date = LocalDate.now(clock);
        DailyGameDay day = loadDay(userId, date);
        List<DailyGameOverviewCardDTO> games = day.challenges().stream()
                .sorted(Comparator.comparing(DailyChallenge::getGameType))
                .map(challenge -> overviewCard(userId, challenge,
                        day.resultsByChallengeId().get(challenge.getId())))
                .toList();
        int totalMaxScore = games.stream().mapToInt(DailyGameOverviewCardDTO::maxAttempts).sum();
        int currentScore = games.stream().mapToInt(DailyGameOverviewCardDTO::score).sum();
        Instant nextResetAt = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new DailyGamesOverviewDTO(date, nextResetAt, totalMaxScore, currentScore, games);
    }

    @Override
    public DailyGameTodayResponseDTO getDay(UUID userId, LocalDate challengeDate) {
        DailyGameDay day = loadDay(userId, challengeDate);
        List<DailyChallenge> challenges = day.challenges();
        Map<UUID, UserDailyGameResult> results = day.resultsByChallengeId();
        List<UUID> challengeIds = challenges.stream().map(DailyChallenge::getId).toList();
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
                challenge, result, hints, challengeDate.equals(LocalDate.now(clock)), majorRoles);
    }

    @Override
    public DailyGameStateDTO getGameToday(UUID userId, DailyGameType gameType, boolean majorRoles) {
        return getGame(userId, LocalDate.now(clock), gameType, majorRoles);
    }

    @Override
    public DailyGameAttemptResponseDTO submitAttempt(
            UUID userId, DailyGameType gameType, DailyGameAttemptRequest request) {
        return submitAttempt(userId, LocalDate.now(clock), gameType, request, true);
    }

    @Override
    public DailyGameAttemptResponseDTO submitAttempt(
            UUID userId, DailyGameType gameType, DailyGameAttemptRequest request, boolean majorRoles) {
        return submitAttempt(userId, LocalDate.now(clock), gameType, request, majorRoles);
    }

    @Override
    public DailyGameAttemptResponseDTO submitAttempt(
            UUID userId, LocalDate challengeDate, DailyGameType gameType, DailyGameAttemptRequest request) {
        return submitAttempt(userId, challengeDate, gameType, request, true);
    }

    @Override
    public DailyGameAttemptResponseDTO submitAttempt(
            UUID userId, LocalDate challengeDate, DailyGameType gameType, DailyGameAttemptRequest request,
            boolean majorRoles) {
        return toRawAttemptResponse(mutateAttempt(userId, challengeDate, gameType, request, majorRoles), majorRoles);
    }

    MutationResult mutateAttempt(UUID userId, LocalDate challengeDate, DailyGameType gameType,
                                 DailyGameAttemptRequest request, boolean majorRoles) {
        requireGameType(gameType);
        assertAvailableDate(challengeDate);
        LocalDateTime now = LocalDateTime.now(clock);
        DailyChallenge challenge = challengeRepository.findByChallengeDateAndGameType(challengeDate, gameType)
                .orElseThrow(DailyGamesUnavailableException::new);

        UserDailyGameResult initialResult = resultRepository
                .findByUserIdAndDailyChallengeIdIn(userId, List.of(challenge.getId()))
                .stream()
                .findFirst()
                .orElse(null);
        if (initialResult != null) {
            assertOpenAndHasAttempts(initialResult, gameType);
        }

        DailyGameCandidateIdentity candidate = candidateValidator.validate(gameType, request);
        DailyGameGuessFeedbackDTO episodeFeedback = guessFeedback(gameType, challenge, candidate);
        DailyGameInfoFeedbackDTO infoFeedback = infoFeedback(gameType, challenge, candidate);
        DailyGameFilmographyFeedbackDTO filmographyFeedback = filmographyFeedback(
                gameType, challenge, candidate, majorRoles);
        newTransactionExecutor.runInNewTransaction(() ->
                resultRepository.insertIfAbsent(UUID.randomUUID(), userId, challenge.getId(), now));
        return newTransactionExecutor.runInNewTransaction(() -> {
        UserDailyGameResult result = resultRepository
                .findByUserIdAndDailyChallengeIdForUpdate(userId, challenge.getId())
                .orElseThrow(() -> new IllegalStateException("Daily game result was not created"));
        assertOpenAndHasAttempts(result, gameType);
        assertNotPreviouslyGuessed(challenge, result, candidate);
        if (request.shareOnCompletion() != null) {
            result.setShareOnCompletion(request.shareOnCompletion());
        }
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
        if (result.getStatus() != DailyGameResultStatus.IN_PROGRESS && result.isShareOnCompletion()) {
            result.markSharedAt(now);
        }
        appendAttemptDetails(challenge, result, attemptNumber, candidate, episodeFeedback, infoFeedback,
                filmographyFeedback);
        resultRepository.save(result);
        DailyGameCandidateDTO candidateDto = new DailyGameCandidateDTO(
                candidate.targetKind(), candidate.tmdbId(), candidate.personTmdbId(), candidate.seriesTmdbId(),
                candidate.seasonNumber(), candidate.episodeNumber(), candidate.title(), candidate.imageUrl(),
                candidate.date());
        DailyGameAttemptDTO current = new DailyGameAttemptDTO(attemptNumber, candidateDto,
                episodeFeedback, infoFeedback, filmographyFeedback);
        return new MutationResult(challenge, result, attemptDetailsCodec.read(result.getAttemptDetails()),
                current, episodeFeedback);
        });
    }

    @Override
    @Transactional
    public DailyGameAttemptResponseDTO giveUp(UUID userId, DailyGameType gameType) {
        return giveUp(userId, LocalDate.now(clock), gameType);
    }

    @Override
    @Transactional
    public DailyGameAttemptResponseDTO giveUp(UUID userId, LocalDate challengeDate, DailyGameType gameType) {
        return toRawAttemptResponse(mutateGiveUp(userId, challengeDate, gameType), true);
    }

    MutationResult mutateGiveUp(UUID userId, LocalDate challengeDate, DailyGameType gameType) {
        requireGameType(gameType);
        assertAvailableDate(challengeDate);
        LocalDateTime now = LocalDateTime.now(clock);
        DailyChallenge challenge = challengeRepository.findByChallengeDateAndGameType(challengeDate, gameType)
                .orElseThrow(DailyGamesUnavailableException::new);
        newTransactionExecutor.runInNewTransaction(() ->
                resultRepository.insertIfAbsent(UUID.randomUUID(), userId, challenge.getId(), now));
        return newTransactionExecutor.runInNewTransaction(() -> {
        UserDailyGameResult result = resultRepository
                .findByUserIdAndDailyChallengeIdForUpdate(userId, challenge.getId())
                .orElseThrow(() -> new IllegalStateException("Daily game result was not created"));
        assertOpenAndHasAttempts(result, gameType);
        result.setStatus(DailyGameResultStatus.FAILED);
        result.setScore(0);
        result.setCompletedAt(now);
        result.setUpdatedAt(now);
        if (result.isShareOnCompletion()) {
            result.markSharedAt(now);
        }
        return new MutationResult(challenge, result, attemptDetailsCodec.read(result.getAttemptDetails()),
                null, null);
        });
    }

    private DailyGameAttemptResponseDTO toRawAttemptResponse(MutationResult mutation, boolean majorRoles) {
        DailyChallenge challenge = mutation.challenge();
        List<DailyChallengeHint> hints = hintRepository.findByDailyChallengeIdOrderByPositionAsc(challenge.getId());
        return responseAssembler.toAttemptResponse(challenge, mutation.result(), hints,
                mutation.feedback(), challenge.getChallengeDate().equals(LocalDate.now(clock)),
                majorRoles, mutation.currentAttempt());
    }

    record MutationResult(DailyChallenge challenge, UserDailyGameResult result,
                          List<DailyGameAttemptDTO> attempts, DailyGameAttemptDTO currentAttempt,
                          DailyGameGuessFeedbackDTO feedback) {
        MutationResult {
            attempts = List.copyOf(attempts);
        }
    }

    private void appendAttemptDetails(
            DailyChallenge challenge,
            UserDailyGameResult result,
            int attemptNumber,
            DailyGameCandidateIdentity candidate,
            DailyGameGuessFeedbackDTO episodeFeedback,
            DailyGameInfoFeedbackDTO infoFeedback,
            DailyGameFilmographyFeedbackDTO filmographyFeedback) {
        if (!challenge.getChallengeDate().equals(LocalDate.now(clock))) {
            return;
        }
        DailyGameCandidateDTO candidateDto = new DailyGameCandidateDTO(
                candidate.targetKind(), candidate.tmdbId(), candidate.personTmdbId(), candidate.seriesTmdbId(),
                candidate.seasonNumber(), candidate.episodeNumber(), candidate.title(), candidate.imageUrl(),
                candidate.date());
        result.setAttemptDetails(attemptDetailsCodec.append(
                result.getAttemptDetails(),
                new DailyGameAttemptDTO(attemptNumber, candidateDto, episodeFeedback, infoFeedback,
                        filmographyFeedback)));
    }

    private void assertNotPreviouslyGuessed(DailyChallenge challenge, UserDailyGameResult result,
                                             DailyGameCandidateIdentity candidate) {
        if (!challenge.getChallengeDate().equals(LocalDate.now(clock))) {
            return;
        }
        String candidateKey = candidate.answerKey();
        boolean alreadyGuessed = attemptDetailsCodec.read(result.getAttemptDetails()).stream()
                .map(DailyGameAttemptDTO::candidate)
                .filter(prior -> prior != null && prior.targetKind() != null)
                .map(prior -> new DailyGameCandidateIdentity(prior.targetKind(), prior.tmdbId(),
                        prior.personTmdbId(), prior.seriesTmdbId(), prior.seasonNumber(),
                        prior.episodeNumber()).answerKey())
                .anyMatch(candidateKey::equals);
        if (alreadyGuessed) {
            throw new ConflictException("This candidate was already guessed");
        }
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

    private DailyGameFilmographyFeedbackDTO filmographyFeedback(
            DailyGameType gameType, DailyChallenge challenge, DailyGameCandidateIdentity candidate, boolean majorRoles) {
        if (filmographyService == null
                || (gameType != DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY
                && gameType != DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY)) {
            return null;
        }
        return filmographyService.compare(challenge, candidate.personTmdbId(), majorRoles);
    }

    private DailyGameDay loadDay(UUID userId, LocalDate challengeDate) {
        assertAvailableDate(challengeDate);
        List<DailyChallenge> challenges = challengeRepository
                .findByChallengeDateOrderByGameTypeAsc(challengeDate);
        requireCompleteDailySet(challenges);
        List<UUID> challengeIds = challenges.stream().map(DailyChallenge::getId).toList();
        Map<UUID, UserDailyGameResult> results = resultRepository
                .findByUserIdAndDailyChallengeIdIn(userId, challengeIds)
                .stream()
                .collect(Collectors.toMap(result -> result.getDailyChallenge().getId(), Function.identity()));
        return new DailyGameDay(challenges, results);
    }

    private DailyGameOverviewCardDTO overviewCard(UUID userId, DailyChallenge challenge,
            UserDailyGameResult result) {
        DailyGameOverviewCardDTO card = responseAssembler.toOverviewCard(challenge, result);
        if (card.imageUrl() == null
                || card.status() == com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus.COMPLETED
                || card.status() == com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus.FAILED) {
            return card;
        }
        List<String> paths = challenge.getDisplaySnapshot() != null
                && challenge.getDisplaySnapshot().get("imagePaths") instanceof List<?> raw
                ? raw.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of(challenge.getImagePath());
        List<String> visible = new java.util.ArrayList<>();
        for (int index = 0; index < card.visibleImageUrls().size(); index++) {
            DailyGameMediaSlot slot = challenge.getGameType() == DailyGameType.EPISODE_BY_FRAME
                    ? DailyGameMediaSlot.values()[index + 1] : DailyGameMediaSlot.CLUE;
            visible.add(mediaService.issue(userId, challenge.getId(), slot,
                    challenge.getGameType() == DailyGameType.EPISODE_BY_FRAME
                            ? DailyGameImageVariant.ORIGINAL
                            : List.of(DailyGameImageVariant.BLUR_26, DailyGameImageVariant.BLUR_20,
                            DailyGameImageVariant.BLUR_14, DailyGameImageVariant.BLUR_9,
                            DailyGameImageVariant.BLUR_5, DailyGameImageVariant.BLUR_2)
                            .get(Math.min(card.attemptsUsed(), 5)),
                    paths.get(Math.min(index, paths.size() - 1))).url());
        }
        return new DailyGameOverviewCardDTO(card.gameType(), card.targetKind(), card.maxAttempts(),
                card.attemptsUsed(), card.attemptsRemaining(), card.status(), card.score(),
                visible.isEmpty() ? null : visible.getLast(), List.copyOf(visible));
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

    private record DailyGameDay(
            List<DailyChallenge> challenges,
            Map<UUID, UserDailyGameResult> resultsByChallengeId) {
    }
}
