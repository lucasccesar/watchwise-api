package com.watchwise.watchwise_api.seriesprogress.service;

import com.watchwise.watchwise_api.seriesprogress.dto.IndividualSeriesProgressDTO;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface SeriesProgressReader {

    Optional<IndividualSeriesProgressDTO> readStartedSeries(UUID viewerId, String seriesTmdbId);

    SeriesProgressReadResult readForSeriesIds(
            UUID viewerId, Collection<String> seriesTmdbIds);
}
