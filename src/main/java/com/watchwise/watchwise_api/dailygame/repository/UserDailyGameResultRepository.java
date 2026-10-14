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

import java.math.BigDecimal;
import java.time.LocalDate;
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

        Long getScore();

        Long getGamesPlayed();
    }

    interface DailyGameRankingViewProjection {

        Long getRank();

        UUID getUserId();

        String getName();

        String getUsername();

        String getProfilePicture();

        Long getScore();

        Long getDaysPlayed();

        Long getGamesPlayed();

        Long getSolvedGames();

        Long getAttemptsUsed();

        BigDecimal getAverageAttempts();
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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE user_daily_game_results result
            SET attempt_details = NULL
            FROM daily_challenges challenge
            WHERE challenge.id = result.daily_challenge_id
              AND challenge.challenge_date < :currentDate
            """, nativeQuery = true)
    int clearAttemptDetailsBefore(@Param("currentDate") LocalDate currentDate);

    @Query("""
            SELECT result FROM UserDailyGameResult result
            JOIN FETCH result.user
            JOIN FETCH result.dailyChallenge
            WHERE result.user.id IN :userIds
              AND result.sharedAt IS NOT NULL
              AND (
                  CAST(:cursorCreatedAt AS timestamp) IS NULL
                  OR result.sharedAt < :cursorCreatedAt
                  OR (result.sharedAt = :cursorCreatedAt
                      AND :cursorId IS NOT NULL
                      AND result.id < :cursorId)
              )
            ORDER BY result.sharedAt DESC, result.id DESC
            """)
    List<UserDailyGameResult> findFeedCandidates(
            @Param("userIds") Collection<UUID> userIds,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    @Query(value = """
            SELECT ranked.rank AS rank,
                   ranked.user_id AS userId,
                   ranked.username AS username,
                   ranked.profile_picture AS profilePicture,
                   ranked.score AS score,
                   ranked.games_played AS gamesPlayed,
                   ranked.attempts_used AS attemptsUsed
            FROM (
                SELECT u.id AS user_id,
                       u.username AS username,
                       u.profile_picture AS profile_picture,
                       RANK() OVER (
                           ORDER BY SUM(r.score) DESC, SUM(r.attempts_used) ASC
                       ) AS rank,
                       SUM(r.score) AS score,
                       COUNT(*) AS games_played,
                       SUM(r.attempts_used) AS attempts_used
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

    @Query(value = """
            WITH ranked AS (
                SELECT u.id AS user_id,
                       u.name AS name,
                       u.username AS username,
                       u.profile_picture AS profile_picture,
                       RANK() OVER (
                           ORDER BY SUM(r.score) DESC, SUM(r.attempts_used) ASC
                       ) AS rank,
                       SUM(r.score) AS score,
                       COUNT(DISTINCT c.challenge_date) AS days_played,
                       COUNT(*) AS games_played,
                       COUNT(*) FILTER (WHERE r.status = 'COMPLETED') AS solved_games,
                       SUM(r.attempts_used) AS attempts_used,
                       AVG(r.attempts_used) AS average_attempts
                FROM user_daily_game_results r
                JOIN daily_challenges c ON c.id = r.daily_challenge_id
                JOIN users u ON u.id = r.user_id
                WHERE r.status IN ('COMPLETED', 'FAILED')
                  AND (:gameType IS NULL OR c.game_type = :gameType)
                GROUP BY u.id, u.name, u.username, u.profile_picture
            )
            SELECT ranked.rank AS rank,
                   ranked.user_id AS userId,
                   ranked.name AS name,
                   ranked.username AS username,
                   ranked.profile_picture AS profilePicture,
                   ranked.score AS score,
                   ranked.days_played AS daysPlayed,
                   ranked.games_played AS gamesPlayed,
                   ranked.solved_games AS solvedGames,
                   ranked.attempts_used AS attemptsUsed,
                   ranked.average_attempts AS averageAttempts
            FROM ranked
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
    Page<DailyGameRankingViewProjection> findRankingViewByGameType(
            @Param("gameType") String gameType, Pageable pageable);

    @Query(value = """
            WITH ranked AS (
                SELECT u.id AS user_id,
                       u.name AS name,
                       u.username AS username,
                       u.profile_picture AS profile_picture,
                       RANK() OVER (
                           ORDER BY SUM(r.score) DESC, SUM(r.attempts_used) ASC
                       ) AS rank,
                       SUM(r.score) AS score,
                       COUNT(DISTINCT c.challenge_date) AS days_played,
                       COUNT(*) AS games_played,
                       COUNT(*) FILTER (WHERE r.status = 'COMPLETED') AS solved_games,
                       SUM(r.attempts_used) AS attempts_used,
                       AVG(r.attempts_used) AS average_attempts
                FROM user_daily_game_results r
                JOIN daily_challenges c ON c.id = r.daily_challenge_id
                JOIN users u ON u.id = r.user_id
                WHERE r.status IN ('COMPLETED', 'FAILED')
                  AND (:gameType IS NULL OR c.game_type = :gameType)
                GROUP BY u.id, u.name, u.username, u.profile_picture
            )
            SELECT ranked.rank AS rank,
                   ranked.user_id AS userId,
                   ranked.name AS name,
                   ranked.username AS username,
                   ranked.profile_picture AS profilePicture,
                   ranked.score AS score,
                   ranked.days_played AS daysPlayed,
                   ranked.games_played AS gamesPlayed,
                   ranked.solved_games AS solvedGames,
                   ranked.attempts_used AS attemptsUsed,
                   ranked.average_attempts AS averageAttempts
            FROM ranked
            WHERE ranked.user_id = :userId
            """, nativeQuery = true)
    Optional<DailyGameRankingViewProjection> findRankingViewerByGameType(
            @Param("gameType") String gameType, @Param("userId") UUID userId);

    @Query(value = """
            WITH ranked AS (
                SELECT RANK() OVER (
                           ORDER BY SUM(r.score) DESC, SUM(r.attempts_used) ASC
                       ) AS rank,
                       SUM(r.score) AS score
                FROM user_daily_game_results r
                JOIN daily_challenges c ON c.id = r.daily_challenge_id
                WHERE r.status IN ('COMPLETED', 'FAILED')
                  AND (:gameType IS NULL OR c.game_type = :gameType)
                GROUP BY r.user_id
            )
            SELECT MIN(ranked.score)
            FROM ranked
            WHERE ranked.rank <= :topLimit
            """, nativeQuery = true)
    Optional<Long> findTop200CutoffScoreByGameType(
            @Param("gameType") String gameType, @Param("topLimit") int topLimit);

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
