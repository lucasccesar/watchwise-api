package com.watchwise.watchwise_api.diarycompletion.model;

import java.time.Instant;
import java.util.Objects;

public record VersionedCompletionScope(
        CompletionScope scope,
        String scheduleVersion,
        Instant validUntil) {

    public VersionedCompletionScope {
        Objects.requireNonNull(scope, "scope is required");
        if (scheduleVersion == null || scheduleVersion.isBlank()) {
            throw new IllegalArgumentException("scheduleVersion is required");
        }
        Objects.requireNonNull(validUntil, "validUntil is required");
        if (Instant.MAX.equals(validUntil)) {
            throw new IllegalArgumentException("validUntil must be finite");
        }
    }
}
