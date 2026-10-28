package com.watchwise.watchwise_api.platformimport.model;

import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

public record ImportReadContext(UUID ownerId, UUID jobId, String storageKey, String sourceNamespace,
                                ZoneId timeZone, long expectedBytes, String expectedSha256) {
    public ImportReadContext {
        Objects.requireNonNull(ownerId);
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(storageKey);
        Objects.requireNonNull(sourceNamespace);
        Objects.requireNonNull(timeZone);
        Objects.requireNonNull(expectedSha256);
        if (expectedBytes < 0) throw new IllegalArgumentException("Import artifact size cannot be negative");
    }

    public ImportArtifactReference artifact() {
        return new ImportArtifactReference(storageKey, expectedBytes, expectedSha256);
    }
}
