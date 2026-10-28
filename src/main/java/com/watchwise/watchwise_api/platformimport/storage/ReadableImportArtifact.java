package com.watchwise.watchwise_api.platformimport.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public interface ReadableImportArtifact extends AutoCloseable {
    Path path();
    InputStream openStream() throws IOException;
    @Override
    void close();
}
