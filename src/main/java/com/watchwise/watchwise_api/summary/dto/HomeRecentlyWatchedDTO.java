package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record HomeRecentlyWatchedDTO(
        UUID id,
        HomeContentReferenceDTO content,
        Integer score,
        LocalDate watchedDate,
        String customPosterUrl,
        List<String> companionProfilePictures,
        ContentCardDTO card
) {
    public HomeRecentlyWatchedDTO {
        companionProfilePictures = companionProfilePictures == null ? List.of() : List.copyOf(companionProfilePictures);
    }

    public HomeRecentlyWatchedDTO(UUID id, HomeContentReferenceDTO content, Integer score, LocalDate watchedDate,
            String customPosterUrl, List<String> companionProfilePictures) {
        this(id, content, score, watchedDate, customPosterUrl, companionProfilePictures, null);
    }
}
