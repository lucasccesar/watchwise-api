package com.watchwise.watchwise_api.platformimport.dto;

import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ImportResolutionDTO(
        @Min(0) long previewVersion,
        @NotEmpty @Size(max = 200) List<@NotNull @Valid ImportResolutionOperation> operations
) {
    public ImportResolutionDTO {
        operations = operations == null ? null : List.copyOf(operations);
    }
}
