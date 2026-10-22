package com.watchwise.watchwise_api.platformimport.dto;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.ImportSelection;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.List;

public record ImportSelectionDTO(
        @Min(0) long previewVersion,
        boolean history,
        boolean watchlist,
        @Size(max = 2000) List<@NotBlank @Size(max = 1000) String> listIds,
        JsonNode metadata
) {
    public ImportSelectionDTO {
        listIds = listIds == null ? List.of() : List.copyOf(listIds);
    }

    public ImportSelection toModel() {
        LinkedHashSet<String> selectedIds = new LinkedHashSet<>(listIds);
        if (selectedIds.size() != listIds.size()) {
            throw new BadRequestException("Selected list identifiers must be unique");
        }
        return new ImportSelection(history, watchlist, selectedIds, metadata);
    }
}
