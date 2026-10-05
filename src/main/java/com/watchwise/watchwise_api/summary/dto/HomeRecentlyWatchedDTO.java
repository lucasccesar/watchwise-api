package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record HomeRecentlyWatchedDTO(
        UUID id,
        ContentRefDTO content,
        Integer score,
        LocalDate watchedDate,
        String customPosterUrl,
        List<String> companionProfilePictures
) {
    public HomeRecentlyWatchedDTO {
        companionProfilePictures = companionProfilePictures == null ? List.of() : List.copyOf(companionProfilePictures);
    }
}
