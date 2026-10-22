package com.watchwise.watchwise_api.platformimport.storage;

import com.watchwise.watchwise_api.platformimport.model.StoredImportFile;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

public interface ImportFileStore {
    void validateKey(String generatedKey);

    default StoredImportFile store(InputStream input, long limit) throws IOException {
        return store(input, limit, UUID.randomUUID().toString());
    }
    StoredImportFile store(InputStream input, long limit, String generatedKey) throws IOException;
    InputStream open(String generatedKey) throws IOException;
    void delete(String generatedKey) throws IOException;
}
