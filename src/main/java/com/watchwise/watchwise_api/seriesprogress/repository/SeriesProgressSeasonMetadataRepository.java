package com.watchwise.watchwise_api.seriesprogress.repository;

import com.watchwise.watchwise_api.seriesprogress.entity.SeriesProgressSeasonMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeriesProgressSeasonMetadataRepository
        extends JpaRepository<SeriesProgressSeasonMetadata, UUID> {

    List<SeriesProgressSeasonMetadata> findAllBySeriesTmdbIdIn(Collection<String> seriesTmdbIds);

    Optional<SeriesProgressSeasonMetadata> findBySeriesTmdbIdAndSeasonNumber(
            String seriesTmdbId, Integer seasonNumber);

    void deleteAllBySeriesTmdbIdIn(Collection<String> seriesTmdbIds);
}
