package com.watchwise.watchwise_api.platformimport.dto;

public record ImportProgressDTO(
        long totalItems,
        long processedItems,
        long appliedItems,
        long skippedItems
) {
    public ImportProgressDTO {
        if (totalItems < 0 || appliedItems < 0 || skippedItems < 0
                || appliedItems > totalItems || skippedItems > totalItems
                || appliedItems > totalItems - skippedItems
                || processedItems != appliedItems + skippedItems) {
            throw new IllegalArgumentException("Import progress counts are inconsistent");
        }
    }
}
