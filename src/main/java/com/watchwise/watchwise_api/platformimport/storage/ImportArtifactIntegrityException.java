package com.watchwise.watchwise_api.platformimport.storage;

public final class ImportArtifactIntegrityException extends RuntimeException {
    private final Reason reason;

    public ImportArtifactIntegrityException(Reason reason, Throwable cause) {
        super("Stored import artifact is unavailable", cause);
        if (reason == null) throw new IllegalArgumentException("Import artifact failure reason is required");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        MISSING,
        CORRUPT
    }
}
