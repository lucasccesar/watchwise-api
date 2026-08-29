package com.watchwise.watchwise_api.top5entry.dto;

import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record Top5EntryPatchDTO(
        @Size(max = 2048) @URL String customPosterUrl
) {
}
