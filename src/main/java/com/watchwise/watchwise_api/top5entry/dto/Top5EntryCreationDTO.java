package com.watchwise.watchwise_api.top5entry.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record Top5EntryCreationDTO(
        @NotBlank String tmdbId,
        @Min(1) @Max(5) Integer position
) {
}