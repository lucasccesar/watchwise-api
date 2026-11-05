package com.watchwise.watchwise_api.top5entry.dto;

import com.watchwise.watchwise_api.common.validation.TmdbPosterPath;
import jakarta.validation.constraints.Size;

public record Top5EntryPatchDTO(
        @Size(max = 2048) @TmdbPosterPath String customPosterUrl
) {
}
