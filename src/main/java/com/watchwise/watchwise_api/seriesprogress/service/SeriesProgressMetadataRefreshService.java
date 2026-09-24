package com.watchwise.watchwise_api.seriesprogress.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface SeriesProgressMetadataRefreshService {

    Snapshot refreshIfMissingOrExpired(String seriesTmdbId, LocalDate today);

    Snapshot refresh(String seriesTmdbId, TmdbTvFullDetails tvDetails, LocalDate today);

    record Snapshot(SeriesSnapshot series, List<SeasonSnapshot> seasons) {

        public Snapshot {
            seasons = List.copyOf(seasons);
        }
    }

    record SeriesSnapshot(
            String seriesTmdbId,
            Integer regularReleasedEpisodeCount,
            Integer totalKnownRuntime,
            Integer knownRuntimeEpisodeCount,
            java.time.LocalDate lastReleasedEpisodeDate,
            LocalDateTime refreshedAt,
            LocalDateTime runtimeVerifiedAt) {
    }

    record SeasonSnapshot(
            String seriesTmdbId,
            Integer seasonNumber,
            Integer regularReleasedEpisodeCount,
            Integer totalKnownRuntime,
            Integer knownRuntimeEpisodeCount,
            java.time.LocalDate lastReleasedEpisodeDate,
            LocalDateTime refreshedAt) {
    }
}
