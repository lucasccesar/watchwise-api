package com.watchwise.watchwise_api.platformimport.model;

import java.util.UUID;

public record PreparedListImport(
        ImportSourceKey sourceKey,
        ImportSourceKey operationKey,
        String name,
        String description,
        UUID parentItemId,
        long itemCount,
        UUID linkedListId) {

    public PreparedListImport {
        if (sourceKey == null || operationKey == null || parentItemId == null || itemCount < 0) {
            throw new IllegalArgumentException("Prepared list import fields are required");
        }
    }
}
