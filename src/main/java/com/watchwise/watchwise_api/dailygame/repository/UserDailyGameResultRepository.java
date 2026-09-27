package com.watchwise.watchwise_api.dailygame.repository;

import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserDailyGameResultRepository extends JpaRepository<UserDailyGameResult, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT result FROM UserDailyGameResult result
            WHERE result.user.id = :userId
              AND result.dailyChallenge.id = :dailyChallengeId
            """)
    Optional<UserDailyGameResult> findByUserIdAndDailyChallengeIdForUpdate(
            @Param("userId") UUID userId, @Param("dailyChallengeId") UUID dailyChallengeId);

    List<UserDailyGameResult> findByUserIdAndDailyChallengeIdIn(
            UUID userId, Collection<UUID> dailyChallengeIds);

    @Modifying
    @Query(value = """
            INSERT INTO user_daily_game_results (
                id,
                user_id,
                daily_challenge_id,
                attempts_used,
                score,
                status,
                completed_at,
                created_at,
                updated_at
            ) VALUES (
                :id,
                :userId,
                :challengeId,
                0,
                0,
                'IN_PROGRESS',
                NULL,
                :now,
                :now
            )
            ON CONFLICT (user_id, daily_challenge_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("challengeId") UUID challengeId,
            @Param("now") LocalDateTime now);
}
