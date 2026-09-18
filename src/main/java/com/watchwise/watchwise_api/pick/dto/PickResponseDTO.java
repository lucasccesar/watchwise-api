package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PickResponseDTO(UUID id, PicksTemplatePreviewDTO picksTemplate, UUID userId, PickVisibility visibility,
                              LocalDateTime createdAt, LocalDateTime updatedAt, PickProgress progress,
                              List<PickSelectionDTO> selections, Integer likesCount, long commentsCount,
                              Boolean isLikedByViewer) {
    public PickResponseDTO(UUID id, PicksTemplatePreviewDTO picksTemplate, UUID userId, PickVisibility visibility,
            LocalDateTime createdAt, LocalDateTime updatedAt, PickProgress progress,
            List<PickSelectionDTO> selections) {
        this(id, picksTemplate, userId, visibility, createdAt, updatedAt, progress, selections, 0, 0, false);
    }
}
