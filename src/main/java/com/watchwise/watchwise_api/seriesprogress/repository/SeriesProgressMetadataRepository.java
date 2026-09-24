package com.watchwise.watchwise_api.seriesprogress.repository;

import com.watchwise.watchwise_api.seriesprogress.entity.SeriesProgressMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SeriesProgressMetadataRepository extends JpaRepository<SeriesProgressMetadata, String> {

    @Query("""
            SELECT metadata.seriesTmdbId AS seriesTmdbId,
                   metadata.regularReleasedEpisodeCount AS regularReleasedEpisodeCount,
                   metadata.totalKnownRuntime AS totalKnownRuntime,
                   metadata.knownRuntimeEpisodeCount AS knownRuntimeEpisodeCount,
                   metadata.lastReleasedEpisodeDate AS lastReleasedEpisodeDate,
                   metadata.refreshedAt AS refreshedAt,
                   metadata.runtimeVerifiedAt AS runtimeVerifiedAt
            FROM SeriesProgressMetadata metadata
            WHERE metadata.seriesTmdbId IN :seriesTmdbIds
            """)
    List<SeriesProgressMetadataProjection> findAllBySeriesTmdbIdIn(
            @Param("seriesTmdbIds") Collection<String> seriesTmdbIds);

    void deleteAllBySeriesTmdbIdIn(Collection<String> seriesTmdbIds);

    interface SeriesProgressMetadataProjection {

        String getSeriesTmdbId();

        Integer getRegularReleasedEpisodeCount();

        Integer getTotalKnownRuntime();

        Integer getKnownRuntimeEpisodeCount();

        java.time.LocalDate getLastReleasedEpisodeDate();

        java.time.LocalDateTime getRefreshedAt();

        java.time.LocalDateTime getRuntimeVerifiedAt();
    }

}
