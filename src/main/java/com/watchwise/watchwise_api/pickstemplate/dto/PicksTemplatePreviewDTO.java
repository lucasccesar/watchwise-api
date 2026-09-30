package com.watchwise.watchwise_api.pickstemplate.dto;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PicksTemplatePreviewDTO(
        UUID id,
        UserPreviewDTO creator,
        PickOrigin origin,
        String name,
        String description,
        String coverImage,
        LocalDateTime createdAt,
        long picksCount,
        long categoriesCount,
        List<String> categoryNames,
        Integer likesCount,
        long commentsCount,
        Boolean isLikedByViewer,
        long myPicksCount,
        UUID latestMyPickId,
        List<CommentResponseDTO> recentComments
) {
    public PicksTemplatePreviewDTO {
        recentComments = recentComments == null ? List.of() : recentComments;
    }

    public PicksTemplatePreviewDTO(UUID id, UserPreviewDTO creator, PickOrigin origin, String name,
            String description, String coverImage, LocalDateTime createdAt, long picksCount, long categoriesCount,
            List<String> categoryNames, Integer likesCount, long commentsCount, Boolean isLikedByViewer,
            long myPicksCount, UUID latestMyPickId) {
        this(id, creator, origin, name, description, coverImage, createdAt, picksCount, categoriesCount,
                categoryNames, likesCount, commentsCount, isLikedByViewer, myPicksCount, latestMyPickId, List.of());
    }

    public PicksTemplatePreviewDTO(UUID id, PickOrigin origin, String name, String description, String coverImage) {
        this(id, null, origin, name, description, coverImage, null, 0, 0, List.of(), 0, 0, false, 0, null);
    }
}
