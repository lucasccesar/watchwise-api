package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeasonProgressDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserListProgressItemDTO(
        UUID id,
        ContentRefDTO content,
        Integer position,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String customPosterUrl,
        SeriesInProgressResponseDTO seriesProgress,
        SeasonProgressDTO seasonProgress) {
}
