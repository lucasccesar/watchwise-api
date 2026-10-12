package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PickTemplatePickViewDTO(
        UUID id,
        UserPreviewDTO user,
        PickVisibility visibility,
        LocalDateTime createdAt,
        PickProgress progress,
        long answeredCategoriesCount,
        long totalCategoriesCount,
        List<PickAnswerViewDTO> answeredCategories,
        Integer likesCount,
        long commentsCount,
        Boolean isLikedByViewer) {

    public PickTemplatePickViewDTO {
        answeredCategories = answeredCategories == null ? List.of() : List.copyOf(answeredCategories);
    }
}
