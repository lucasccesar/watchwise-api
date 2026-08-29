package com.watchwise.watchwise_api.top5entry.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record Top5EntryCreationDTO(
        @NotBlank String tmdbId,
        @Min(1) @Max(5) Integer position,
        @Size(max = 2048) @URL String customPosterUrl
) {
    public Top5EntryCreationDTO(String tmdbId, Integer position) {
        this(tmdbId, position, null);
    }
}
