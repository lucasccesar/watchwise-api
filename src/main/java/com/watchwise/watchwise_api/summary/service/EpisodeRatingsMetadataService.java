package com.watchwise.watchwise_api.summary.service;

import java.util.Collection;
import java.util.Map;

public interface EpisodeRatingsMetadataService {

    Map<String, EpisodeRatingsMetadata> resolve(Collection<String> seriesTmdbIds, String language);

    EpisodeRatingsMetadata resolve(String seriesTmdbId, String language);
}
