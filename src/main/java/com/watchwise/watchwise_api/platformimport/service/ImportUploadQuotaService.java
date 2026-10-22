package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.StoredImportFile;

import java.util.UUID;

public interface ImportUploadQuotaService {
    ImportJob reserve(UUID ownerId, ImportInputKind inputKind, ImportSourceFormat sourceFormat,
                      ImportPlatform platform, String sourceAccountId, String timeZone,
                      String storageKey, long uploadBytes);

    void markStored(UUID ownerId, UUID jobId, StoredImportFile storedFile);

    void markFailed(UUID ownerId, UUID jobId);
}
