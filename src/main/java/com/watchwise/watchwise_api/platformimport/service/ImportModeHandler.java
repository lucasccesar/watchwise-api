package com.watchwise.watchwise_api.platformimport.service;

import java.io.InputStream;
import com.watchwise.watchwise_api.platformimport.model.ImportInventory;
import com.watchwise.watchwise_api.platformimport.model.ImportModeKey;
import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportSelection;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;

import java.util.function.Consumer;

public interface ImportModeHandler {
    ImportModeKey mode();

    default void validateUpload(InputStream input) {
    }

    ImportInventory inspect(ImportReadContext context);

    void normalize(ImportReadContext context, ImportSelection selection, Consumer<NormalizedImportRecord> sink);

    default void validateResolved(ImportExecutionContext context) {
    }

    default void prepareForApply(ImportExecutionContext context) {
    }
}
