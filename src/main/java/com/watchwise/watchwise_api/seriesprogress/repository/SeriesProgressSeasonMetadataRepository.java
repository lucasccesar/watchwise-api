package com.watchwise.watchwise_api.seriesprogress.repository;

import com.watchwise.watchwise_api.seriesprogress.entity.SeriesProgressSeasonMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeriesProgressSeasonMetadataRepository
        extends JpaRepository<SeriesProgressSeasonMetadata, UUID> {

    @Query("""
            SELECT metadata.seriesTmdbId AS seriesTmdbId,
                   metadata.seasonNumber AS seasonNumber,
                   metadata.regularReleasedEpisodeCount AS regularReleasedEpisodeCount,
                   metadata.totalKnownRuntime AS totalKnownRuntime,
                   metadata.knownRuntimeEpisodeCount AS knownRuntimeEpisodeCount,
                   metadata.lastReleasedEpisodeDate AS lastReleasedEpisodeDate,
                   metadata.refreshedAt AS refreshedAt
            FROM SeriesProgressSeasonMetadata metadata
            WHERE metadata.seriesTmdbId IN :seriesTmdbIds
            """)
    List<SeriesProgressSeasonMetadataProjection> findAllBySeriesTmdbIdIn(
            @Param("seriesTmdbIds") Collection<String> seriesTmdbIds);

    Optional<SeriesProgressSeasonMetadata> findBySeriesTmdbIdAndSeasonNumber(
            String seriesTmdbId, Integer seasonNumber);

    void deleteAllBySeriesTmdbIdIn(Collection<String> seriesTmdbIds);

    interface SeriesProgressSeasonMetadataProjection {

        String getSeriesTmdbId();

        Integer getSeasonNumber();

        Integer getRegularReleasedEpisodeCount();

        Integer getTotalKnownRuntime();

        Integer getKnownRuntimeEpisodeCount();

        java.time.LocalDate getLastReleasedEpisodeDate();

        java.time.LocalDateTime getRefreshedAt();
    }

}
