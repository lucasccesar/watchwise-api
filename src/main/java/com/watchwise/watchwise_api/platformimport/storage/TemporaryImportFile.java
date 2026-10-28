package com.watchwise.watchwise_api.platformimport.storage;

import java.nio.file.Path;

public interface TemporaryImportFile extends AutoCloseable {
    Path path();

    @Override
    void close();
}
