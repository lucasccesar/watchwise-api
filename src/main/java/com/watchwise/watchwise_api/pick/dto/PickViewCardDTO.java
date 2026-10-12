package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public record PickViewCardDTO(
        UUID id,
        PicksTemplatePreviewDTO picksTemplate,
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

    private static final int MAX_ANSWERED_CATEGORIES = 5;

    public PickViewCardDTO {
        answeredCategories = answeredCategories == null
                ? List.of()
                : List.copyOf(answeredCategories.subList(0, Math.min(answeredCategories.size(), MAX_ANSWERED_CATEGORIES)));
    }

    public PickViewCardDTO(UUID id, PicksTemplatePreviewDTO picksTemplate, UserPreviewDTO user,
            PickVisibility visibility, LocalDateTime createdAt, PickProgress progress,
            long answeredCategoriesCount, long totalCategoriesCount,
            Collection<PickAnsweredCategoryPreviewDTO> answeredCategories, Integer likesCount,
            long commentsCount, Boolean isLikedByViewer) {
        this(id, picksTemplate, user, visibility, createdAt, progress, answeredCategoriesCount, totalCategoriesCount,
                answeredCategories == null ? List.of() : answeredCategories.stream()
                        .map(PickViewCardDTO::toAnswerView)
                        .toList(), likesCount, commentsCount, isLikedByViewer);
    }

    private static PickAnswerViewDTO toAnswerView(PickAnsweredCategoryPreviewDTO answer) {
        return new PickAnswerViewDTO(answer.categoryId(), answer.name(), answer.group(), answer.displayOrder(),
                null, null, answer.target(), null, true, answer.isValid());
    }
}
