package com.watchwise.watchwise_api.comment.dto;

import com.watchwise.watchwise_api.user.dto.PublicUserDTO;

import java.time.LocalDateTime;
import java.util.UUID;

public record CommentResponseDTO(
        UUID id,
        PublicUserDTO user,
        UUID contentId,
        UUID listId,
        UUID diaryEntryId,
        UUID parentCommentId,
        String text,
        Boolean containsSpoiler,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
