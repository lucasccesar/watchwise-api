package com.watchwise.watchwise_api.platformimport.model;

import com.watchwise.watchwise_api.platformimport.storage.ImportStorageKeys;

public record ImportArtifactReference(String key, long bytes, String sha256) {
    public ImportArtifactReference {
        ImportStorageKeys.validate(key);
        if (bytes < 0) throw new IllegalArgumentException("Import artifact size cannot be negative");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Import artifact digest must be a SHA-256 hex value");
        }
    }
}
