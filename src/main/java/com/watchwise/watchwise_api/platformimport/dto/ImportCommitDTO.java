package com.watchwise.watchwise_api.platformimport.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ImportCommitDTO(
        @Min(0) long previewVersion,
        @NotBlank @Pattern(regexp = "(?i)[a-f0-9]{64}") String digest
) {
}
