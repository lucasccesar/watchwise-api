package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;

public interface ImportUnitTerminalListener {
    void onTerminal(ImportExecutionContext context, ImportItem item);
}
