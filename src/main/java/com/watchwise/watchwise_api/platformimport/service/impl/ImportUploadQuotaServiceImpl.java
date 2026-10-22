package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.platformimport.entity.ImportFileCleanupTask;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.StoredImportFile;
import com.watchwise.watchwise_api.platformimport.repository.ImportFileCleanupTaskRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.service.ImportUploadQuotaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.Set;
import java.util.UUID;

@Service
public class ImportUploadQuotaServiceImpl implements ImportUploadQuotaService {
    private static final String GLOBAL_LOCK_ID = "import-upload:quota:global";
    private static final Set<ImportJobState> PENDING_STATES = Set.of(
            ImportJobState.ANALYZING,
            ImportJobState.AWAITING_SELECTION,
            ImportJobState.RESOLVING,
            ImportJobState.READY,
            ImportJobState.IMPORTING);

    private final AdvisoryLock advisoryLock;
    private final ImportJobRepository importJobRepository;
    private final ImportFileCleanupTaskRepository cleanupTaskRepository;
    private final ImportLimits importLimits;
    private final Clock clock;

    public ImportUploadQuotaServiceImpl(AdvisoryLock advisoryLock, ImportJobRepository importJobRepository,
                                        ImportFileCleanupTaskRepository cleanupTaskRepository,
                                        ImportLimits importLimits, Clock clock) {
        this.advisoryLock = advisoryLock;
        this.importJobRepository = importJobRepository;
        this.cleanupTaskRepository = cleanupTaskRepository;
        this.importLimits = importLimits;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ImportJob reserve(UUID ownerId, ImportInputKind inputKind, ImportSourceFormat sourceFormat,
                             ImportPlatform platform, String sourceAccountId, String timeZone,
                             String storageKey, long uploadBytes) {
        if (ownerId == null || inputKind == null || sourceFormat == null || storageKey == null) {
            throw new IllegalArgumentException("Import reservation data is incomplete");
        }
        if (uploadBytes < 1 || uploadBytes > importLimits.maxUploadBytes()) {
            throw new IllegalArgumentException("Import reservation size is invalid");
        }
        advisoryLock.lock(GLOBAL_LOCK_ID);
        advisoryLock.lock("import-upload:quota:owner:" + ownerId);

        long pendingJobs = importJobRepository.countPendingByOwnerId(ownerId, PENDING_STATES);
        long ownerBytes = importJobRepository.sumPendingUploadBytesByOwnerId(ownerId, PENDING_STATES);
        long globalBytes = importJobRepository.sumPendingUploadBytes(PENDING_STATES);
        if (pendingJobs >= importLimits.maxPendingJobsPerUser()) {
            throw new TooManyRequestsException("Too many pending import jobs");
        }
        if (exceedsQuota(ownerBytes, uploadBytes, importLimits.maxPendingUploadBytesPerUser())) {
            throw new TooManyRequestsException("Pending import upload quota exceeded for this user");
        }
        if (exceedsQuota(globalBytes, uploadBytes, importLimits.maxPendingUploadBytesGlobal())) {
            throw new TooManyRequestsException("Global pending import upload quota exceeded");
        }

        String normalizedAccountId = normalizeSourceAccountId(sourceAccountId);
        String namespace = sourceNamespace(ownerId, platform, normalizedAccountId);
        if (namespace.length() > 255) throw new IllegalArgumentException("Import source namespace is too long");
        ZoneId zone = parseZone(timeZone);
        LocalDateTime now = LocalDateTime.now(clock);
        ImportJobState initialState = inputKind == ImportInputKind.PLATFORM_ARCHIVE
                ? ImportJobState.ANALYZING : ImportJobState.RESOLVING;
        return importJobRepository.save(ImportJob.builder()
                .userId(ownerId)
                .inputKind(inputKind)
                .sourceFormat(sourceFormat)
                .platform(platform)
                .sourceAccountId(normalizedAccountId)
                .sourceNamespace(namespace)
                .state(initialState)
                .previewVersion(0)
                .selectionJson("{}")
                .timezone(zone.getId())
                .storageKey(storageKey)
                .uploadSizeBytes(uploadBytes)
                .workPhase(inputKind == ImportInputKind.PLATFORM_ARCHIVE ? "INSPECT" : "RESOLVE")
                .workPending(false)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Override
    @Transactional
    public void markStored(UUID ownerId, UUID jobId, StoredImportFile storedFile) {
        ImportJob job = lockOwnedJob(ownerId, jobId);
        if (storedFile == null || !job.getStorageKey().equals(storedFile.key())
                || job.getUploadSizeBytes() != storedFile.bytes() || storedFile.sha256() == null
                || !storedFile.sha256().matches("[a-f0-9]{64}")) {
            throw new IllegalStateException("Stored import file does not match its reservation");
        }
        job.setZipDigest(storedFile.sha256());
        job.setWorkPending(true);
        job.setNextRunAt(LocalDateTime.now(clock));
        job.setUpdatedAt(LocalDateTime.now(clock));
    }

    @Override
    @Transactional
    public void markFailed(UUID ownerId, UUID jobId) {
        advisoryLock.lock(GLOBAL_LOCK_ID);
        advisoryLock.lock("import-upload:quota:owner:" + ownerId);
        ImportJob job = lockOwnedJob(ownerId, jobId);
        job.setState(ImportJobState.FAILED);
        job.setUploadSizeBytes(0);
        job.setWorkPending(false);
        job.setWorkPhase(null);
        job.setNextRunAt(null);
        job.setUpdatedAt(LocalDateTime.now(clock));
        LocalDateTime now = LocalDateTime.now(clock);
        cleanupTaskRepository.save(ImportFileCleanupTask.builder()
                .storageKey(job.getStorageKey())
                .readyAt(now)
                .attempts(0)
                .createdAt(now)
                .build());
    }

    private boolean exceedsQuota(long current, long requested, long limit) {
        return current < 0 || requested > limit || current > limit - requested;
    }

    private ImportJob lockOwnedJob(UUID ownerId, UUID jobId) {
        return importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)
                .orElseThrow(() -> new NotFoundException("Import job not found"));
    }

    private String normalizeSourceAccountId(String sourceAccountId) {
        if (sourceAccountId == null || sourceAccountId.isBlank()) return null;
        String normalized = sourceAccountId.trim();
        if (normalized.length() > 220 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Import source account identifier is invalid");
        }
        return normalized;
    }

    private String sourceNamespace(UUID ownerId, ImportPlatform platform, String accountId) {
        if (platform == null) return "WATCHWISE:TEMPLATE:" + ownerId;
        if (accountId == null) return platform.name() + ":OWNER:" + ownerId;
        return platform.name() + ":ACCOUNT:" + accountId;
    }

    private ZoneId parseZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(timeZone.trim());
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Import time zone is invalid", exception);
        }
    }
}
