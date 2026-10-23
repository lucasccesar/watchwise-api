package com.watchwise.watchwise_api.contentrating.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ContentRatingUpsertDTO(
        @NotNull @Min(1) @Max(10) Integer score,
        @PositiveOrZero Long expectedVersion) {
}
