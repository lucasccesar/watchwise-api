package com.watchwise.watchwise_api.seriesprogress.repository;

import com.watchwise.watchwise_api.seriesprogress.entity.SeriesProgressMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SeriesProgressMetadataRepository extends JpaRepository<SeriesProgressMetadata, String> {

    List<SeriesProgressMetadata> findAllBySeriesTmdbIdIn(Collection<String> seriesTmdbIds);

    void deleteAllBySeriesTmdbIdIn(Collection<String> seriesTmdbIds);
}
