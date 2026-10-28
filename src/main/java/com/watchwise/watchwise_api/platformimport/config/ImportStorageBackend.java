package com.watchwise.watchwise_api.platformimport.config;

public enum ImportStorageBackend {
    LOCAL,
    R2;

    public static ImportStorageBackend parse(String value) {
        if (value == null) throw new IllegalStateException("Missing app.import.storage.backend");
        return switch (value) {
            case "local" -> LOCAL;
            case "r2" -> R2;
            default -> throw new IllegalStateException("Invalid app.import.storage.backend");
        };
    }
}
