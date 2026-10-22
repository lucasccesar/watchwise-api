package com.watchwise.watchwise_api.platformimport.model;

public record ImportPlanVersion(long version, String digest) {
    public ImportPlanVersion {
        if (version < 0 || digest == null || digest.isBlank()) {
            throw new IllegalArgumentException("Import plan version is invalid");
        }
    }
}
