package com.watchwise.watchwise_api.platformimport.storage;

import com.watchwise.watchwise_api.common.exception.ServiceUnavailableException;

public final class ImportStorageUnavailableException extends ServiceUnavailableException {
    public static final String PUBLIC_MESSAGE = "Import file storage is temporarily unavailable";

    public ImportStorageUnavailableException(Throwable cause) {
        super(PUBLIC_MESSAGE, cause);
    }
}
