package com.watchwise.watchwise_api.content.dto;

import java.util.List;

public record ContentPageMetadataDTO(
        String originalLanguage,
        String certification,
        String homepageUrl,
        String tmdbUrl,
        String imdbUrl,
        List<ContentPageWatchProviderDTO> watchProviders) {

    public ContentPageMetadataDTO {
        watchProviders = watchProviders == null ? List.of() : List.copyOf(watchProviders);
    }
}
