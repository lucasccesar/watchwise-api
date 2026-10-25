package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.model.PreparedWatchlistImport;

public interface ImportWatchlistWriter {
    int apply(ImportExecutionContext context, PreparedWatchlistImport plan);
}
