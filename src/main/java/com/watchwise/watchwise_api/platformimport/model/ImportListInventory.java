package com.watchwise.watchwise_api.platformimport.model;

public record ImportListInventory(String sourceId, String name, String description, long itemCount, String coverage) {
    public ImportListInventory {
        if (sourceId == null || sourceId.isBlank() || itemCount < 0) {
            throw new IllegalArgumentException("Import list inventory is invalid");
        }
    }
}
