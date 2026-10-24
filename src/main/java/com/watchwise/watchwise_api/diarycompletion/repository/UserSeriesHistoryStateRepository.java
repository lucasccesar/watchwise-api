package com.watchwise.watchwise_api.diarycompletion.repository;

import com.watchwise.watchwise_api.diarycompletion.entity.UserSeriesHistoryState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserSeriesHistoryStateRepository extends JpaRepository<UserSeriesHistoryState, UserSeriesHistoryState.Key> {
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            INSERT INTO user_series_history_states(user_id, series_tmdb_id, history_revision, computed_revision, schedule_version, version)
            VALUES (:userId, :seriesTmdbId, 1, 0, 0, 0)
            ON CONFLICT (user_id, series_tmdb_id) DO UPDATE SET
              history_revision = user_series_history_states.history_revision + 1,
              version = user_series_history_states.version + 1
            """, nativeQuery = true)
    void incrementHistoryRevision(UUID userId, String seriesTmdbId);

    @Query("""
            SELECT state FROM UserSeriesHistoryState state
            WHERE state.id.userId = :userId
              AND state.id.seriesTmdbId IN :seriesTmdbIds
            """)
    List<UserSeriesHistoryState> findByUserIdAndSeriesTmdbIdIn(
            @Param("userId") UUID userId,
            @Param("seriesTmdbIds") Collection<String> seriesTmdbIds);

    @Query(value = "SELECT history_revision FROM user_series_history_states WHERE user_id = :userId AND series_tmdb_id = :seriesTmdbId",
            nativeQuery = true)
    Optional<Long> findHistoryRevision(@Param("userId") UUID userId, @Param("seriesTmdbId") String seriesTmdbId);
}
