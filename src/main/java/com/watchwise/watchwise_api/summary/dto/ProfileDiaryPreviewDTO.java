package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ProfileDiaryPreviewDTO(
        UUID id,
        ContentRefDTO content,
        Integer score,
        LocalDate watchedDate,
        Integer watchNumber,
        String customPosterUrl,
        List<UserPreviewDTO> watchedWith,
        ContentCardDTO card
) {
    public ProfileDiaryPreviewDTO {
        watchedWith = watchedWith == null ? List.of() : List.copyOf(watchedWith);
    }

    public ProfileDiaryPreviewDTO(UUID id, ContentRefDTO content, Integer score, LocalDate watchedDate,
            Integer watchNumber, String customPosterUrl, List<UserPreviewDTO> watchedWith) {
        this(id, content, score, watchedDate, watchNumber, customPosterUrl, watchedWith, null);
    }
}
