package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.PreparedListImport;

import java.util.UUID;

public interface ImportListWriter {
    ImportSourceKey operationKey(ImportExecutionContext context, ImportItem metadataItem);

    ImportSourceKey memberKey(ImportExecutionContext context, ImportItem item);

    UUID apply(ImportExecutionContext context, PreparedListImport plan);

    void skipMember(ImportExecutionContext context, ImportItem item, String reason);
}
