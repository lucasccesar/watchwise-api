package com.watchwise.watchwise_api.platformimport.service;

import java.util.UUID;

public record ImportExecutionContext(
        UUID ownerId,
        UUID jobId,
        long previewVersion,
        long planGeneration,
        String sourceNamespace,
        UUID leaseToken) {

    public ImportExecutionContext {
        if (ownerId == null || jobId == null || previewVersion < 0 || planGeneration < 0
                || sourceNamespace == null || sourceNamespace.isBlank() || leaseToken == null) {
            throw new IllegalArgumentException("Import execution context is invalid");
        }
    }
}
