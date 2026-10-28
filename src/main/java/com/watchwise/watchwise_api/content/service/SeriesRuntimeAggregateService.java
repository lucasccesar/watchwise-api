package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.entity.Content;

public interface SeriesRuntimeAggregateService {

    SeriesRuntimeResolution resolve(Content content, TmdbTvFullDetails freshDetails, String language);

    SeriesRuntimeResolution resolve(Content content, TmdbTvFullDetails freshDetails, String language,
            long refreshGeneration);

    void initializeIfMissing(Content content, TmdbTvDetails freshDetails);

    void initializeIfMissing(Content content, TmdbTvDetails freshDetails, long refreshGeneration);

    void incrementForNewEpisode(Content content, Integer seasonNumber, Integer episodeNumber,
            Integer reportedEpisodeCount);

    void incrementForNewEpisode(Content content, Integer seasonNumber, Integer episodeNumber,
            Integer reportedEpisodeCount, long refreshGeneration);

    void reconcileBeforeFreezing(Content content, TmdbTvDetails freshDetails);

    void reconcileBeforeFreezing(Content content, TmdbTvDetails freshDetails, long refreshGeneration);
}
