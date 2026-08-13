package com.watchwise.watchwise_api.top5entry.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record Top5EntryCreationDTO(
        @NotNull @Valid ContentRefCreationDTO content,
        @Min(1) @Max(5) Integer position
) {
}