package com.watchwise.watchwise_api.contentposter.dto;

import com.watchwise.watchwise_api.common.validation.TmdbPosterPath;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserContentPosterRequestDTO(
        @NotBlank
        @Size(max = 2048)
        @TmdbPosterPath
        String customPosterUrl
) {
}
