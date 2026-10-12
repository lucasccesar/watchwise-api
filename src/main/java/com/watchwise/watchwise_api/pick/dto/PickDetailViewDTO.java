package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateResponseDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PickDetailViewDTO(
        UUID id,
        PicksTemplateResponseDTO picksTemplate,
        UserPreviewDTO user,
        PickVisibility visibility,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        PickProgress progress,
        long answeredCategoriesCount,
        long totalCategoriesCount,
        List<PickAnswerViewDTO> answers,
        Integer likesCount,
        long commentsCount,
        Boolean isLikedByViewer) {

    public PickDetailViewDTO {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }
}
