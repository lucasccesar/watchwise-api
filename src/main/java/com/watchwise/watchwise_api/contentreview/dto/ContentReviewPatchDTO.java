package com.watchwise.watchwise_api.contentreview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContentReviewPatchDTO(@NotBlank @Size(max = 10000) String comment) {
}
