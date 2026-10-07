package com.watchwise.watchwise_api.content.dto;

import java.util.List;

public record ContentPageMetadataDTO(
        String originalLanguage,
        String certification,
        String homepageUrl,
        String tmdbUrl,
        String imdbUrl,
        List<ContentPageWatchProviderDTO> watchProviders,
        String presentationPosterPath,
        List<CrewMemberDTO> presentationCrew,
        Boolean crewInherited) {

    public ContentPageMetadataDTO(
            String originalLanguage,
            String certification,
            String homepageUrl,
            String tmdbUrl,
            String imdbUrl,
            List<ContentPageWatchProviderDTO> watchProviders) {
        this(originalLanguage, certification, homepageUrl, tmdbUrl, imdbUrl, watchProviders,
                null, List.of(), false);
    }

    public ContentPageMetadataDTO {
        watchProviders = watchProviders == null ? List.of() : List.copyOf(watchProviders);
        presentationCrew = presentationCrew == null ? List.of() : List.copyOf(presentationCrew);
    }

    public ContentPageMetadataDTO withEpisodePresentation(
            String posterPath, List<CrewMemberDTO> crew) {
        return new ContentPageMetadataDTO(
                originalLanguage, certification, homepageUrl, tmdbUrl, imdbUrl, watchProviders,
                posterPath, crew, true);
    }
}
