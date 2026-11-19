package com.watchwise.watchwise_api.platformimport.dto;

import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ImportResolutionDTO(
        @Min(0) long previewVersion,
        @NotEmpty @Size(max = 200) List<@NotNull @Valid ImportResolutionOperation> operations,
        @Size(max = 200) Map<@NotNull UUID, @NotNull @Min(1) Long> expectedReviewRevisions
) {
    public ImportResolutionDTO(long previewVersion, List<ImportResolutionOperation> operations) {
        this(previewVersion, operations, Map.of());
    }

    public ImportResolutionDTO {
        operations = operations == null ? null : List.copyOf(operations);
        expectedReviewRevisions = expectedReviewRevisions == null ? Map.of() : Map.copyOf(expectedReviewRevisions);
    }
}
