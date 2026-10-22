package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.dto.ImportCommitDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportJobDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportPreviewDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportResolutionDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportSelectionDTO;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface ImportJobService {
    ImportJobDTO create(UUID ownerId, MultipartFile file, ImportInputKind inputKind,
                        ImportSourceFormat sourceFormat, ImportPlatform platform,
                        String sourceAccountId, String timeZone);

    ImportJobDTO get(UUID ownerId, UUID jobId);

    ImportJobDTO select(UUID ownerId, UUID jobId, ImportSelectionDTO selection);

    ImportJobDTO resolve(UUID ownerId, UUID jobId, ImportResolutionDTO resolution);

    ImportPreviewDTO preview(UUID ownerId, UUID jobId, int page, int size);

    ImportJobDTO commit(UUID ownerId, UUID jobId, ImportCommitDTO commit);

    ImportJobDTO cancel(UUID ownerId, UUID jobId);

    ImportJobDTO retry(UUID ownerId, UUID jobId);

    ImportPreviewDTO result(UUID ownerId, UUID jobId, int page, int size);
}
