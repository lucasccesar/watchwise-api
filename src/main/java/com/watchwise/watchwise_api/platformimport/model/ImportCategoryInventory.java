package com.watchwise.watchwise_api.platformimport.model;

public record ImportCategoryInventory(ImportRecordKind kind, long count, boolean available) {
    public ImportCategoryInventory {
        if (kind == null || count < 0) {
            throw new IllegalArgumentException("Import category inventory is invalid");
        }
    }
}
