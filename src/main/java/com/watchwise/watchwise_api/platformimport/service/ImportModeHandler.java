package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.model.ImportInventory;
import com.watchwise.watchwise_api.platformimport.model.ImportModeKey;
import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportSelection;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;

import java.util.function.Consumer;

public interface ImportModeHandler {
    ImportModeKey mode();

    ImportInventory inspect(ImportReadContext context);

    void normalize(ImportReadContext context, ImportSelection selection, Consumer<NormalizedImportRecord> sink);
}
