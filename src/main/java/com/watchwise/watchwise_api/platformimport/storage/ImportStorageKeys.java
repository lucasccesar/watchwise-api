package com.watchwise.watchwise_api.platformimport.storage;

import com.watchwise.watchwise_api.common.exception.BadRequestException;

public final class ImportStorageKeys {
    private static final String UUID_PATTERN = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";
    private static final String OBJECT_PREFIX = "imports/";

    private ImportStorageKeys() { }

    public static void validate(String generatedKey) {
        if (generatedKey == null || !generatedKey.matches(UUID_PATTERN)) {
            throw new BadRequestException("Invalid import storage key");
        }
    }

    public static String objectKey(String generatedKey) {
        validate(generatedKey);
        return OBJECT_PREFIX + generatedKey;
    }
}
