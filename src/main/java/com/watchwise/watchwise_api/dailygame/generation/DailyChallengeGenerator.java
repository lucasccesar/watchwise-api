package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

public interface DailyChallengeGenerator {

    DailyGameType gameType();

    Optional<DailyChallengeCandidate> generate(LocalDate challengeDate);

    default Optional<DailyChallengeCandidate> generate(LocalDate challengeDate, Set<String> excludedAnswerKeys) {
        return generate(challengeDate)
                .filter(candidate -> excludedAnswerKeys == null || !excludedAnswerKeys.contains(candidate.answerKey()));
    }
}
