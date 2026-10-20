package com.watchwise.watchwise_api.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommentUpdateDTO(
        @NotBlank @Size(max = 280) String text,
        Boolean containsSpoiler
) {
}
