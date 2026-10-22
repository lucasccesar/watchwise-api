package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.platformimport.dto.ImportCommitDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportJobDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportPreviewDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportPreviewItemDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportResolutionDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportSelectionDTO;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.mapper.ImportJobMapper;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportModeKey;
import com.watchwise.watchwise_api.platformimport.model.ImportPlanVersion;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportSelection;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.StoredImportFile;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportJobService;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.service.ImportModeRegistry;
import com.watchwise.watchwise_api.platformimport.service.ImportPreviewService;
import com.watchwise.watchwise_api.platformimport.service.ImportUploadQuotaService;
import com.watchwise.watchwise_api.platformimport.storage.ImportFileStore;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.Locale;
import java.util.UUID;

@Service
public class ImportJobServiceImpl implements ImportJobService {
    private final ImportJobRepository importJobRepository;
    private final ImportModeRegistry importModeRegistry;
    private final ImportPreviewService importPreviewService;
    private final ImportUploadQuotaService uploadQuotaService;
    private final ImportFileStore importFileStore;
    private final ImportJobMapper importJobMapper;
    private final ImportLimits importLimits;
    private final Clock clock;

    public ImportJobServiceImpl(ImportJobRepository importJobRepository, ImportModeRegistry importModeRegistry,
                                ImportPreviewService importPreviewService,
                                ImportUploadQuotaService uploadQuotaService, ImportFileStore importFileStore,
                                ImportJobMapper importJobMapper, ImportLimits importLimits, Clock clock) {
        this.importJobRepository = importJobRepository;
        this.importModeRegistry = importModeRegistry;
        this.importPreviewService = importPreviewService;
        this.uploadQuotaService = uploadQuotaService;
        this.importFileStore = importFileStore;
        this.importJobMapper = importJobMapper;
        this.importLimits = importLimits;
        this.clock = clock;
    }

    @Override
    public ImportJobDTO create(UUID ownerId, MultipartFile file, ImportInputKind inputKind,
                               ImportSourceFormat sourceFormat, ImportPlatform platform,
                               String sourceAccountId, String timeZone) {
        if (ownerId == null) throw new BadRequestException("Authenticated import owner is required");
        validateMode(inputKind, sourceFormat, platform);
        importModeRegistry.require(new ImportModeKey(inputKind, sourceFormat));
        validateFile(file, sourceFormat);
        validateSourceAccountId(sourceAccountId);
        validateTimeZone(timeZone);

        String storageKey = UUID.randomUUID().toString();
        ImportJob reservation = uploadQuotaService.reserve(ownerId, inputKind, sourceFormat, platform,
                sourceAccountId, timeZone, storageKey, file.getSize());
        StoredImportFile storedFile;
        try (InputStream input = file.getInputStream()) {
            storedFile = importFileStore.store(input, importLimits.maxUploadBytes(), storageKey);
            if (storedFile.bytes() != file.getSize()) {
                throw new IOException("Stored import size differs from the uploaded size");
            }
            uploadQuotaService.markStored(ownerId, reservation.getId(), storedFile);
        } catch (IOException | RuntimeException failure) {
            try {
                uploadQuotaService.markFailed(ownerId, reservation.getId());
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("Private import storage failed", failure);
        }
        return get(ownerId, reservation.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public ImportJobDTO get(UUID ownerId, UUID jobId) {
        ImportJob job = findOwned(ownerId, jobId);
        ImportPlanVersion version = importPreviewService.currentVersion(ownerId, jobId);
        return importJobMapper.toDto(job, version.digest());
    }

    @Override
    public ImportJobDTO select(UUID ownerId, UUID jobId, ImportSelectionDTO selection) {
        if (selection == null) throw new BadRequestException("Import selection is required");
        ImportSelection model = selection.toModel();
        importPreviewService.select(ownerId, jobId, model, selection.previewVersion());
        return get(ownerId, jobId);
    }

    @Override
    public ImportJobDTO resolve(UUID ownerId, UUID jobId, ImportResolutionDTO resolution) {
        if (resolution == null) throw new BadRequestException("Import resolution is required");
        importPreviewService.resolve(ownerId, jobId, resolution.operations(), resolution.previewVersion());
        return get(ownerId, jobId);
    }

    @Override
    @Transactional(readOnly = true)
    public ImportPreviewDTO preview(UUID ownerId, UUID jobId, int page, int size) {
        return previewPage(ownerId, jobId, page, size);
    }

    @Override
    @Transactional
    public ImportJobDTO commit(UUID ownerId, UUID jobId, ImportCommitDTO commit) {
        if (commit == null) throw new BadRequestException("Import commit is required");
        ImportJob job = importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)
                .orElseThrow(() -> new NotFoundException("Import job not found"));
        ImportPlanVersion currentVersion = importPreviewService.currentVersion(ownerId, jobId);
        if (commit.previewVersion() != job.getPreviewVersion()
                || !currentVersion.digest().equalsIgnoreCase(commit.digest())) {
            throw new ConflictException("Import preview has changed");
        }
        if (job.getState() == ImportJobState.IMPORTING
                || job.getState() == ImportJobState.COMPLETED
                || job.getState() == ImportJobState.COMPLETED_WITH_WARNINGS) {
            return importJobMapper.toDto(job, currentVersion.digest());
        }
        if (job.getState() != ImportJobState.READY) {
            throw new ConflictException("Import preview is not ready to commit");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        job.setState(ImportJobState.IMPORTING);
        job.setWorkPhase("APPLY");
        job.setWorkPending(true);
        job.setNextRunAt(now);
        job.setUpdatedAt(now);
        return importJobMapper.toDto(job, currentVersion.digest());
    }

    @Override
    @Transactional
    public ImportJobDTO cancel(UUID ownerId, UUID jobId) {
        ImportJob job = importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)
                .orElseThrow(() -> new NotFoundException("Import job not found"));
        if (job.getState() == ImportJobState.CANCELLED) return toDto(ownerId, job);
        if (job.getState() == ImportJobState.COMPLETED || job.getState() == ImportJobState.COMPLETED_WITH_WARNINGS
                || job.getState() == ImportJobState.FAILED || job.getState() == ImportJobState.EXPIRED) {
            throw new ConflictException("Import job cannot be cancelled in its current state");
        }
        job.setState(ImportJobState.CANCELLED);
        job.setWorkPending(false);
        job.setNextRunAt(null);
        job.setUpdatedAt(LocalDateTime.now(clock));
        return toDto(ownerId, job);
    }

    @Override
    public ImportJobDTO retry(UUID ownerId, UUID jobId) {
        ImportJob job = findOwned(ownerId, jobId);
        if (job.getState() != ImportJobState.FAILED) {
            throw new ConflictException("Only failed import jobs can be retried");
        }
        if (job.getUploadSizeBytes() < 1) {
            throw new ConflictException("The import source file is no longer available");
        }
        importModeRegistry.require(new ImportModeKey(job.getInputKind(), job.getSourceFormat()));
        try (InputStream ignored = importFileStore.open(job.getStorageKey())) {
        } catch (IOException exception) {
            throw new ConflictException("The import source file is no longer available");
        }
        uploadQuotaService.reactivate(ownerId, jobId);
        return get(ownerId, jobId);
    }

    @Override
    @Transactional(readOnly = true)
    public ImportPreviewDTO result(UUID ownerId, UUID jobId, int page, int size) {
        return previewPage(ownerId, jobId, page, size);
    }

    private ImportPreviewDTO previewPage(UUID ownerId, UUID jobId, int page, int size) {
        ImportJobDTO job = get(ownerId, jobId);
        Page<ImportPreviewItemDTO> items = importPreviewService.preview(ownerId, jobId, page, size);
        return new ImportPreviewDTO(job.id(), job.state(), job.previewVersion(), job.previewDigest(),
                items.getContent(), items.getNumber(), items.getSize(), items.getTotalElements(), items.getTotalPages());
    }

    private ImportJobDTO toDto(UUID ownerId, ImportJob job) {
        ImportPlanVersion version = importPreviewService.currentVersion(ownerId, job.getId());
        return importJobMapper.toDto(job, version.digest());
    }

    private ImportJob findOwned(UUID ownerId, UUID jobId) {
        if (ownerId == null || jobId == null) throw new BadRequestException("Import owner and job are required");
        return importJobRepository.findOwnedById(jobId, ownerId)
                .orElseThrow(() -> new NotFoundException("Import job not found"));
    }

    private void validateMode(ImportInputKind inputKind, ImportSourceFormat sourceFormat, ImportPlatform platform) {
        if (inputKind == null || sourceFormat == null) throw new BadRequestException("Import kind and source format are required");
        boolean compatible = switch (inputKind) {
            case PLATFORM_ARCHIVE -> (sourceFormat == ImportSourceFormat.TRAKT_ZIP && platform == ImportPlatform.TRAKT)
                    || (sourceFormat == ImportSourceFormat.LETTERBOXD_ZIP && platform == ImportPlatform.LETTERBOXD);
            case SINGLE_LIST, SINGLE_WATCHLIST -> (sourceFormat == ImportSourceFormat.TRAKT_JSON && platform == ImportPlatform.TRAKT)
                    || (sourceFormat == ImportSourceFormat.LETTERBOXD_CSV && platform == ImportPlatform.LETTERBOXD);
            case PICKS_TEMPLATE -> sourceFormat == ImportSourceFormat.WATCHWISE_TEMPLATE_JSON && platform == null;
        };
        if (!compatible) throw new BadRequestException("Import kind, source format and platform are incompatible");
    }

    private void validateFile(MultipartFile file, ImportSourceFormat sourceFormat) {
        if (file == null || file.isEmpty()) throw new BadRequestException("An import file is required");
        if (file.getSize() > importLimits.maxUploadBytes()) {
            throw new MaxUploadSizeExceededException(importLimits.maxUploadBytes());
        }
        String name = StringUtils.getFilename(file.getOriginalFilename());
        String extension = name == null || name.lastIndexOf('.') < 0
                ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String expectedExtension = switch (sourceFormat) {
            case TRAKT_ZIP, LETTERBOXD_ZIP -> "zip";
            case TRAKT_JSON, WATCHWISE_TEMPLATE_JSON -> "json";
            case LETTERBOXD_CSV -> "csv";
        };
        if (!expectedExtension.equals(extension)) throw new BadRequestException("File extension does not match the selected import format");
        validateMediaType(file.getContentType(), sourceFormat);
    }

    private void validateMediaType(String contentType, ImportSourceFormat sourceFormat) {
        if (!StringUtils.hasText(contentType) || "application/octet-stream".equalsIgnoreCase(contentType.trim())) return;
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        boolean compatible = switch (sourceFormat) {
            case TRAKT_ZIP, LETTERBOXD_ZIP -> mediaType.equals("application/zip")
                    || mediaType.equals("application/x-zip-compressed");
            case TRAKT_JSON, WATCHWISE_TEMPLATE_JSON -> mediaType.equals("application/json")
                    || mediaType.equals("text/json");
            case LETTERBOXD_CSV -> mediaType.equals("text/csv") || mediaType.equals("application/csv")
                    || mediaType.equals("application/vnd.ms-excel");
        };
        if (!compatible) throw new BadRequestException("Content type does not match the selected import format");
    }

    private void validateSourceAccountId(String sourceAccountId) {
        if (sourceAccountId != null && (sourceAccountId.trim().length() > 220
                || sourceAccountId.chars().anyMatch(Character::isISOControl))) {
            throw new BadRequestException("Import source account identifier is invalid");
        }
    }

    private void validateTimeZone(String timeZone) {
        try {
            if (StringUtils.hasText(timeZone)) ZoneId.of(timeZone.trim());
        } catch (DateTimeException exception) {
            throw new BadRequestException("Import time zone is invalid");
        }
    }
}
