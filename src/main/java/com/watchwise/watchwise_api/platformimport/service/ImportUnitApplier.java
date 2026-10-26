package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.ImportPayloadHash;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;

import java.util.UUID;

public interface ImportUnitApplier {
    boolean supports(ImportRecordKind kind);

    default ImportSourceKey sourceKey(ImportExecutionContext context, ImportItem item) {
        return new ImportSourceKey(item.getSourceNamespace(), item.getObjectKind(), item.getSourceId());
    }

    default String payloadHash(ImportExecutionContext context, ImportItem item) {
        return ImportPayloadHash.sha256(item.getPayloadJson());
    }

    void apply(ImportExecutionContext context, UUID unitId);

    default boolean isComplete(ImportExecutionContext context, UUID unitId) {
        return true;
    }
}
