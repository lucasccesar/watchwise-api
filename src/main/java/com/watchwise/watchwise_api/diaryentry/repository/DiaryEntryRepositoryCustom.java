package com.watchwise.watchwise_api.diaryentry.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DiaryEntryRepositoryCustom {

    default List<DiaryEntryRepository.WatchedEpisodeCoordinateProjection>
    findWatchedEpisodeCoordinatesByUserIdAndSeriesSeasonPairs(
            UUID userId, Collection<SeriesSeasonPair> seriesSeasonPairs) {
        return findWatchedEpisodeCoordinatesByUserIdAndSeriesIdsOrSeriesSeasonPairs(
                userId, List.of(), seriesSeasonPairs);
    }

    List<DiaryEntryRepository.WatchedEpisodeCoordinateProjection>
    findWatchedEpisodeCoordinatesByUserIdAndSeriesIdsOrSeriesSeasonPairs(
            UUID userId,
            Collection<String> seriesTmdbIds,
            Collection<SeriesSeasonPair> seriesSeasonPairs);

    record SeriesSeasonPair(String seriesTmdbId, Integer seasonNumber) {
    }
}
