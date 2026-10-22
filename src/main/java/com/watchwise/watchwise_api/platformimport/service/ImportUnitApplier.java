package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;

import java.util.UUID;

public interface ImportUnitApplier {
    boolean supports(ImportRecordKind kind);

    void apply(ImportExecutionContext context, UUID unitId);
}
