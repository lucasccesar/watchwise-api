package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallengeHint;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.generation.DailyChallengeCandidate;
import com.watchwise.watchwise_api.dailygame.generation.DailyChallengeGenerator;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeHintRepository;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeRepository;
import com.watchwise.watchwise_api.dailygame.service.DailyChallengeGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

@Slf4j
@Service
public class DailyChallengeGenerationServiceImpl implements DailyChallengeGenerationService {

    private final AdvisoryLock advisoryLock;
    private final DailyChallengeRepository challengeRepository;
    private final DailyChallengeHintRepository hintRepository;
    private final Map<DailyGameType, DailyChallengeGenerator> generators;
    private final int generationMaxCandidates;
    private final Clock clock;

    public DailyChallengeGenerationServiceImpl(
            AdvisoryLock advisoryLock,
            DailyChallengeRepository challengeRepository,
            DailyChallengeHintRepository hintRepository,
            List<DailyChallengeGenerator> generators,
            @Value("${app.daily-games.generation-max-candidates}") int generationMaxCandidates,
            Clock clock) {
        this.advisoryLock = advisoryLock;
        this.challengeRepository = challengeRepository;
        this.hintRepository = hintRepository;
        this.generators = indexGenerators(generators);
        this.generationMaxCandidates = generationMaxCandidates;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void ensureGenerated(LocalDate challengeDate) {
        advisoryLock.lock("daily-games|" + challengeDate);
        for (DailyGameType gameType : DailyGameType.values()) {
            generateMissingModality(challengeDate, gameType);
        }
    }

    private void generateMissingModality(LocalDate challengeDate, DailyGameType gameType) {
        if (challengeRepository.existsByChallengeDateAndGameType(challengeDate, gameType)) {
            return;
        }
        DailyChallengeGenerator generator = generators.get(gameType);
        if (generator == null) {
            throw new IllegalStateException("No generator registered for " + gameType);
        }
        Set<String> rejectedAnswerKeys = new HashSet<>();
        for (int attempt = 0; attempt < generationMaxCandidates; attempt++) {
            DailyChallengeCandidate candidate = generator.generate(challengeDate, rejectedAnswerKeys).orElse(null);
            if (!isValidCandidate(candidate, gameType)) {
                addRejectedAnswerKey(rejectedAnswerKeys, candidate);
                continue;
            }
            advisoryLock.lock("daily-games-answer|" + gameType + "|" + candidate.answerKey());
            if (challengeRepository.existsByGameTypeAndAnswerKey(gameType, candidate.answerKey())) {
                rejectedAnswerKeys.add(candidate.answerKey());
                continue;
            }
            persistChallenge(challengeDate, candidate);
            return;
        }
        log.error("Daily game generation exhausted eligible candidates for {} on {}", gameType, challengeDate);
    }

    private void addRejectedAnswerKey(Set<String> rejectedAnswerKeys, DailyChallengeCandidate candidate) {
        if (candidate != null && candidate.answerKey() != null && !candidate.answerKey().isBlank()) {
            rejectedAnswerKeys.add(candidate.answerKey());
        }
    }

    private void persistChallenge(LocalDate challengeDate, DailyChallengeCandidate candidate) {
        LocalDateTime now = LocalDateTime.now(clock);
        DailyChallenge challenge = DailyChallenge.builder()
                .challengeDate(challengeDate)
                .gameType(candidate.gameType())
                .targetKind(candidate.targetKind())
                .targetTmdbId(candidate.targetTmdbId())
                .seriesTmdbId(candidate.seriesTmdbId())
                .seasonNumber(candidate.seasonNumber())
                .episodeNumber(candidate.episodeNumber())
                .answerKey(candidate.answerKey())
                .sourceTmdbId(candidate.sourceTmdbId())
                .imagePath(candidate.imagePath())
                .answerSnapshot(candidate.answerSnapshot())
                .displaySnapshot(candidate.displaySnapshot())
                .createdAt(now)
                .updatedAt(now)
                .build();
        DailyChallenge saved = challengeRepository.saveAndFlush(challenge);
        if (!candidate.hints().isEmpty()) {
            List<DailyChallengeHint> hints = IntStream.range(0, candidate.hints().size())
                    .mapToObj(index -> {
                        DailyChallengeCandidate.HintSnapshot hint = candidate.hints().get(index);
                        return DailyChallengeHint.builder()
                                .dailyChallenge(saved)
                                .position(index + 1)
                                .hintType(hint.hintType())
                                .hintValue(hint.hintValue())
                                .build();
                    })
                    .toList();
            hintRepository.saveAllAndFlush(hints);
        }
    }

    private boolean isValidCandidate(DailyChallengeCandidate candidate, DailyGameType gameType) {
        if (candidate == null || candidate.gameType() != gameType || candidate.targetKind() != gameType.targetKind()
                || candidate.answerKey() == null || candidate.answerKey().isBlank()) {
            return false;
        }
        return switch (candidate.targetKind()) {
            case MOVIE, SERIES, PERSON -> validCoordinate(candidate.targetTmdbId())
                    && candidate.seriesTmdbId() == null
                    && candidate.seasonNumber() == null
                    && candidate.episodeNumber() == null
                    && candidate.answerKey().equals(candidate.targetKind().name() + ":" + candidate.targetTmdbId());
            case EPISODE -> candidate.targetTmdbId() == null
                    && validCoordinate(candidate.seriesTmdbId())
                    && candidate.seasonNumber() != null && candidate.seasonNumber() > 0
                    && candidate.episodeNumber() != null && candidate.episodeNumber() > 0
                    && candidate.answerKey().equals("EPISODE:" + candidate.seriesTmdbId() + ":"
                    + candidate.seasonNumber() + ":" + candidate.episodeNumber());
        };
    }

    private boolean validCoordinate(String value) {
        return value != null && value.matches("[1-9]\\d*");
    }

    private Map<DailyGameType, DailyChallengeGenerator> indexGenerators(List<DailyChallengeGenerator> generators) {
        EnumMap<DailyGameType, DailyChallengeGenerator> indexed = new EnumMap<>(DailyGameType.class);
        if (generators != null) {
            generators.forEach(generator -> {
                if (generator != null && indexed.put(generator.gameType(), generator) != null) {
                    throw new IllegalArgumentException("Duplicate daily game generator: " + generator.gameType());
                }
            });
        }
        return Map.copyOf(indexed);
    }
}
