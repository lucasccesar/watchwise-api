package com.watchwise.watchwise_api.dailygame.repository;

import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DailyChallengeRepository extends JpaRepository<DailyChallenge, UUID> {

    Optional<DailyChallenge> findByChallengeDateAndGameType(LocalDate challengeDate, DailyGameType gameType);

    boolean existsByChallengeDateAndGameType(LocalDate challengeDate, DailyGameType gameType);

    boolean existsByGameTypeAndAnswerKey(DailyGameType gameType, String answerKey);

    List<DailyChallenge> findByChallengeDateOrderByGameTypeAsc(LocalDate challengeDate);

    List<DailyChallenge> findByChallengeDateBetweenOrderByChallengeDateDescGameTypeAsc(
            LocalDate startDate, LocalDate endDate);
}
