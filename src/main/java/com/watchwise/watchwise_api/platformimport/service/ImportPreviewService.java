package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.dto.ImportPreviewItemDTO;
import com.watchwise.watchwise_api.platformimport.model.ImportPlanVersion;
import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;
import com.watchwise.watchwise_api.platformimport.model.ImportSelection;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

public interface ImportPreviewService {
    ImportPlanVersion select(UUID ownerId, UUID jobId, ImportSelection selection, long expectedVersion);

    ImportPlanVersion resolve(UUID ownerId, UUID jobId, List<ImportResolutionOperation> decisions, long expectedVersion);

    Page<ImportPreviewItemDTO> preview(UUID ownerId, UUID jobId, int page, int size);

    ImportPlanVersion currentVersion(UUID ownerId, UUID jobId);
}
