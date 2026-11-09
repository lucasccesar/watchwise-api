package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentPageMetadataDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;

public interface ContentPageMetadataService {

    ContentPageMetadataDTO getMetadata(Content content, String language, String region);

    ContentPageMetadataDTO getEpisodeMetadata(
            Content content, String language, String region,
            TmdbEpisodeFullDetails episode, TmdbTvFullDetails series);
}
