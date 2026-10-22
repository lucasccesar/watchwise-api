package com.watchwise.watchwise_api.platformimport.model;

import java.util.List;

public record AliasResolution(Status status, List<ImportTarget> candidates) {
    public enum Status { MATCHED, AMBIGUOUS, NOT_FOUND, UNAVAILABLE }

    public AliasResolution {
        if (status == null || candidates == null) {
            throw new IllegalArgumentException("Alias resolution status and candidates are required");
        }
        candidates = List.copyOf(candidates);
        if (status == Status.MATCHED && candidates.size() != 1) {
            throw new IllegalArgumentException("A matched alias must have exactly one candidate");
        }
    }

    public static AliasResolution matched(ImportTarget target) {
        return new AliasResolution(Status.MATCHED, List.of(target));
    }

    public static AliasResolution ambiguous(List<ImportTarget> candidates) {
        return new AliasResolution(Status.AMBIGUOUS, candidates);
    }

    public static AliasResolution notFound() {
        return new AliasResolution(Status.NOT_FOUND, List.of());
    }

    public static AliasResolution unavailable(List<ImportTarget> candidates) {
        return new AliasResolution(Status.UNAVAILABLE, candidates);
    }
}
