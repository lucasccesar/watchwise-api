package com.watchwise.watchwise_api.contentposter.dto;

import com.watchwise.watchwise_api.common.validation.TmdbPosterUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserContentPosterRequestDTO(
        @NotBlank
        @Size(max = 2048)
        @TmdbPosterUrl
        String customPosterUrl
) {
}
