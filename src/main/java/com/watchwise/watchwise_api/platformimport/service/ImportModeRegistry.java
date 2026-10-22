package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.ImportInventory;
import com.watchwise.watchwise_api.platformimport.model.ImportModeKey;
import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportSelection;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;

import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class ImportModeRegistry {
    private final Map<ImportModeKey, ImportModeHandler> handlers;

    public ImportModeRegistry(Collection<ImportModeHandler> handlers) {
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                ImportModeHandler::mode,
                handler -> handler,
                (first, second) -> {
                    throw new IllegalStateException("Duplicate import mode handler");
                }));
    }

    public ImportModeHandler require(ImportModeKey mode) {
        ImportModeHandler handler = handlers.get(mode);
        if (handler == null) {
            throw new BadRequestException("Import mode is not available");
        }
        return handler;
    }

    public ImportInventory inspect(ImportModeKey mode, ImportReadContext context) {
        return require(mode).inspect(context);
    }

    public void normalize(ImportModeKey mode, ImportReadContext context, ImportSelection selection,
                         Consumer<NormalizedImportRecord> sink) {
        require(mode).normalize(context, selection, sink);
    }
}
