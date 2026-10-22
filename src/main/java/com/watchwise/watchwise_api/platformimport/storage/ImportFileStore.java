package com.watchwise.watchwise_api.platformimport.storage;

import com.watchwise.watchwise_api.platformimport.model.StoredImportFile;
import java.io.IOException;
import java.io.InputStream;

public interface ImportFileStore {
    StoredImportFile store(InputStream input, long limit) throws IOException;
    InputStream open(String generatedKey) throws IOException;
    void delete(String generatedKey) throws IOException;
}
