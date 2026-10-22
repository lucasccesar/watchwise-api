package com.watchwise.watchwise_api.platformimport.model;

import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

public record ImportReadContext(UUID ownerId, UUID jobId, String storageKey, String sourceNamespace, ZoneId timeZone) {
    public ImportReadContext {
        Objects.requireNonNull(ownerId);
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(storageKey);
        Objects.requireNonNull(sourceNamespace);
        Objects.requireNonNull(timeZone);
    }
}
