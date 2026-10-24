package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ProfileDiaryPreviewDTO(
        UUID id,
        ProfileDiaryPreviewSource source,
        ContentRefDTO content,
        Integer score,
        LocalDate watchedDate,
        LocalDate activityDate,
        Integer watchNumber,
        String customPosterUrl,
        List<UserPreviewDTO> watchedWith,
        ContentCardDTO card
) {
    public ProfileDiaryPreviewDTO {
        source = source == null ? ProfileDiaryPreviewSource.DIARY_ENTRY : source;
        watchedWith = watchedWith == null ? List.of() : List.copyOf(watchedWith);
    }

    public ProfileDiaryPreviewDTO(UUID id, ContentRefDTO content, Integer score, LocalDate watchedDate,
            Integer watchNumber, String customPosterUrl, List<UserPreviewDTO> watchedWith) {
        this(id, ProfileDiaryPreviewSource.DIARY_ENTRY, content, score, watchedDate, watchedDate,
                watchNumber, customPosterUrl, watchedWith, null);
    }

    public ProfileDiaryPreviewDTO(UUID id, ContentRefDTO content, Integer score, LocalDate watchedDate,
            Integer watchNumber, String customPosterUrl, List<UserPreviewDTO> watchedWith, ContentCardDTO card) {
        this(id, ProfileDiaryPreviewSource.DIARY_ENTRY, content, score, watchedDate, watchedDate,
                watchNumber, customPosterUrl, watchedWith, card);
    }

    public ProfileDiaryPreviewDTO withCard(ContentCardDTO card) {
        return new ProfileDiaryPreviewDTO(id, source, content, score, watchedDate, activityDate,
                watchNumber, customPosterUrl, watchedWith, card);
    }
}
