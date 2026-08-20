package com.watchwise.watchwise_api.dropped.dto;

import jakarta.validation.constraints.Size;

public record DroppedEntryCreationDTO(
        @Size(max = 280) String comment
) {
}