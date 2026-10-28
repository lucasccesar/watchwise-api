package com.watchwise.watchwise_api.platformimport.storage;

public final class ImportStorageKeyCollisionException extends RuntimeException {
    public ImportStorageKeyCollisionException() {
        super("Import storage key already belongs to another upload operation");
    }
}
