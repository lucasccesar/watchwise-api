package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.entity.Content;

public interface SeriesRuntimeAggregateService {

    SeriesRuntimeResolution resolve(Content content, TmdbTvFullDetails freshDetails, String language);

    void initializeIfMissing(Content content, TmdbTvDetails freshDetails);

    void incrementForNewEpisode(Content content, Integer seasonNumber, Integer episodeNumber,
            Integer reportedEpisodeCount);

    void reconcileBeforeFreezing(Content content, TmdbTvDetails freshDetails);
}
