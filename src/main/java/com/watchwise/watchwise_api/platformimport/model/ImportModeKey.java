package com.watchwise.watchwise_api.platformimport.model;

import java.util.Objects;

public record ImportModeKey(ImportInputKind kind, ImportSourceFormat format) {
    public ImportModeKey {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(format);
    }
}
