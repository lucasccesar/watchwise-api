package com.watchwise.watchwise_api.dailygame.repository;

import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    interface DailyGameRankingProjection {

        Long getRank();

        UUID getUserId();

        String getUsername();

        String getProfilePicture();

        Long getTotalScore();

        Long getTotalAttempts();
    }

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

    @Query(value = """
            SELECT ranked.rank AS rank,
                   ranked.user_id AS userId,
                   ranked.username AS username,
                   ranked.profile_picture AS profilePicture,
                   ranked.total_score AS totalScore,
                   ranked.total_attempts AS totalAttempts
            FROM (
                SELECT u.id AS user_id,
                       u.username AS username,
                       u.profile_picture AS profile_picture,
                       RANK() OVER (
                           ORDER BY SUM(r.score) DESC, SUM(r.attempts_used) ASC
                       ) AS rank,
                       SUM(r.score) AS total_score,
                       SUM(r.attempts_used) AS total_attempts
                FROM user_daily_game_results r
                JOIN daily_challenges c ON c.id = r.daily_challenge_id
                JOIN users u ON u.id = r.user_id
                WHERE r.status IN ('COMPLETED', 'FAILED')
                  AND (:gameType IS NULL OR c.game_type = :gameType)
                GROUP BY u.id, u.username, u.profile_picture
            ) ranked
            ORDER BY ranked.rank ASC, ranked.username ASC, ranked.user_id ASC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM (
                SELECT r.user_id
                FROM user_daily_game_results r
                JOIN daily_challenges c ON c.id = r.daily_challenge_id
                WHERE r.status IN ('COMPLETED', 'FAILED')
                  AND (:gameType IS NULL OR c.game_type = :gameType)
                GROUP BY r.user_id
            ) ranked
            """, nativeQuery = true)
    Page<DailyGameRankingProjection> findRankingByGameType(
            @Param("gameType") String gameType, Pageable pageable);

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
