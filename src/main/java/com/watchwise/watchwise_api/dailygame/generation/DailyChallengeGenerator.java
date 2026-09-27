package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.time.LocalDate;
import java.util.Optional;

public interface DailyChallengeGenerator {

    DailyGameType gameType();

    Optional<DailyChallengeCandidate> generate(LocalDate challengeDate);
}
