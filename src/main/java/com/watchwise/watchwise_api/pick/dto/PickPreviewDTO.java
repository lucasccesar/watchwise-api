package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PickPreviewDTO(
        UUID id,
        UserPreviewDTO user,
        PickVisibility visibility,
        LocalDateTime createdAt,
        Integer likesCount,
        long commentsCount,
        Boolean isLikedByViewer,
        List<PickAnsweredCategoryPreviewDTO> answeredCategories,
        List<CommentResponseDTO> recentComments
) {
    public PickPreviewDTO {
        recentComments = recentComments == null ? List.of() : recentComments;
    }

    public PickPreviewDTO(UUID id, UserPreviewDTO user, PickVisibility visibility, LocalDateTime createdAt,
            Integer likesCount, long commentsCount, Boolean isLikedByViewer,
            List<PickAnsweredCategoryPreviewDTO> answeredCategories) {
        this(id, user, visibility, createdAt, likesCount, commentsCount, isLikedByViewer, answeredCategories,
                List.of());
    }
}
