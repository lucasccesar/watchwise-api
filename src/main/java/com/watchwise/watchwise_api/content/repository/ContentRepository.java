package com.watchwise.watchwise_api.content.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ContentRepository extends JpaRepository<Content, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select content from Content content where content.id = :contentId")
    Optional<Content> findByIdForUpdate(@Param("contentId") UUID contentId);

    Optional<Content> findByTmdbIdAndType(String tmdbId, ContentType type);

    Optional<Content> findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
            String seriesTmdbId, Integer seasonNumber, Integer episodeNumber, ContentType type);

    Optional<Content> findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(
            String seriesTmdbId, Integer seasonNumber, ContentType type);

    Optional<Content> findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(String seriesTmdbId, ContentType type);

}
