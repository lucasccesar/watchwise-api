package com.watchwise.watchwise_api.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CommentCreationDTO(
        @NotBlank @Size(max = 280) String text,
        UUID parentCommentId,
        Boolean containsSpoiler
) {
}
