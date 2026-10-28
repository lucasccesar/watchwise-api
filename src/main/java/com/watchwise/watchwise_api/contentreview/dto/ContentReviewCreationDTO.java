package com.watchwise.watchwise_api.contentreview.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ContentReviewCreationDTO(
        @NotBlank @Size(max = 10000) String comment,
        @Min(1) @Max(10) Integer score,
        @PositiveOrZero Long expectedRatingVersion) {
}
