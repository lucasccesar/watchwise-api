package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.entity.ImportFileCleanupTask;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.repository.ImportFileCleanupTaskRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.storage.ImportFileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class ImportCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(ImportCleanupJob.class);
    private static final int MAX_PER_PASS = 200;
    private static final Duration CLEANUP_LEASE = Duration.ofMinutes(2);
    private static final String CLAIM_TASK_SQL = """
            WITH candidate AS (
                SELECT id FROM import_file_cleanup_tasks
                WHERE ready_at <= ?
                ORDER BY ready_at ASC, id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE import_file_cleanup_tasks task
            SET ready_at = ?
            FROM candidate
            WHERE task.id = candidate.id
            RETURNING task.id, task.storage_key, task.attempts
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ImportJobRepository jobRepository;
    private final ImportFileCleanupTaskRepository cleanupTaskRepository;
    private final ImportFileStore fileStore;
    private final Clock clock;
    private final boolean enabled;

    public ImportCleanupJob(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                            ImportJobRepository jobRepository,
                            ImportFileCleanupTaskRepository cleanupTaskRepository,
                            ImportFileStore fileStore, Clock clock,
                            @Value("${app.import.cleanup.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jobRepository = jobRepository;
        this.cleanupTaskRepository = cleanupTaskRepository;
        this.fileStore = fileStore;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.import.cleanup.cron:0 */15 * * * *}", zone = "UTC")
    public void runScheduled() {
        if (enabled) runOnce(clock.instant());
    }

    public void runOnce(Instant now) {
        if (now == null) throw new IllegalArgumentException("Cleanup time is required");
        LocalDateTime localNow = LocalDateTime.ofInstant(now, clock.getZone());
        List<UUID> expiredJobs = jobRepository.findExpiredUploadCandidates(localNow,
                        ImportJobState.IMPORTING, PageRequest.of(0, MAX_PER_PASS))
                .stream().map(ImportJob::getId).toList();
        for (UUID jobId : expiredJobs) cleanupExpiredUpload(jobId, localNow);
        for (int processed = 0; processed < MAX_PER_PASS; processed++) {
            CleanupTask task = claimReadyTask(localNow);
            if (task == null) break;
            processTask(task, localNow);
        }
    }

    private void cleanupExpiredUpload(UUID jobId, LocalDateTime now) {
        String storageKey = transactionTemplate.execute(status -> {
            ImportJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getUploadSizeBytes() <= 0 || job.getExpiresAt() == null
                    || job.getExpiresAt().isAfter(now) || job.getState() == ImportJobState.IMPORTING
                    || (job.getLeaseUntil() != null && job.getLeaseUntil().isAfter(now))) {
                return null;
            }
            if (job.getState() != ImportJobState.EXPIRED
                    && job.getState() != ImportJobState.COMPLETED
                    && job.getState() != ImportJobState.COMPLETED_WITH_WARNINGS) {
                job.setState(ImportJobState.EXPIRED);
                job.setWorkPending(false);
                job.setWorkPhase(null);
                job.setNextRunAt(null);
            }
            job.setLeaseToken(null);
            job.setLeaseUntil(null);
            job.setUpdatedAt(now);
            return job.getStorageKey();
        });
        if (storageKey == null) return;

        try {
            fileStore.validateKey(storageKey);
            fileStore.delete(storageKey);
            transactionTemplate.executeWithoutResult(status -> jdbc.update(
                    "UPDATE import_jobs SET upload_size_bytes = 0, updated_at = ? WHERE id = ? AND storage_key = ?",
                    now, jobId, storageKey));
        } catch (BadRequestException exception) {
            log.error("Expired import file has an invalid storage key for job {}", jobId);
        } catch (IOException | RuntimeException exception) {
            enqueueRetry(storageKey, now);
            log.warn("Could not remove expired import file for job {}", jobId, exception);
        }
    }

    private CleanupTask claimReadyTask(LocalDateTime now) {
        return transactionTemplate.execute(status -> jdbc.query(CLAIM_TASK_SQL,
                (result, row) -> new CleanupTask(result.getObject("id", UUID.class),
                        result.getString("storage_key"), result.getInt("attempts")),
                now, now.plus(CLEANUP_LEASE)).stream().findFirst().orElse(null));
    }

    private void processTask(CleanupTask task, LocalDateTime now) {
        boolean eligible = transactionTemplate.execute(status -> jobRepository.findByStorageKey(task.storageKey())
                .map(ImportCleanupJob::isIrreversiblyTerminal)
                .orElse(true));
        if (!eligible) {
            deferTask(task, now);
            return;
        }
        try {
            fileStore.validateKey(task.storageKey());
            fileStore.delete(task.storageKey());
            transactionTemplate.executeWithoutResult(status -> {
                cleanupTaskRepository.deleteById(task.id());
                jdbc.update("""
                        UPDATE import_jobs SET upload_size_bytes = 0, updated_at = ?
                        WHERE storage_key = ? AND (
                            state IN ('EXPIRED', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'CANCELLED')
                            OR (state = 'FAILED' AND work_phase IS NULL)
                        )
                        """, now, task.storageKey());
            });
        } catch (BadRequestException exception) {
            deferTask(task, now);
            log.error("Import cleanup task has an invalid storage key: {}", task.id());
        } catch (IOException | RuntimeException exception) {
            deferTask(task, now);
            log.warn("Could not remove private import file for cleanup task {}", task.id(), exception);
        }
    }

    private static boolean isIrreversiblyTerminal(ImportJob job) {
        return switch (job.getState()) {
            case CANCELLED, EXPIRED, COMPLETED, COMPLETED_WITH_WARNINGS -> true;
            case FAILED -> job.getWorkPhase() == null;
            default -> false;
        };
    }

    private void enqueueRetry(String storageKey, LocalDateTime now) {
        transactionTemplate.executeWithoutResult(status -> {
            if (cleanupTaskRepository.existsByStorageKey(storageKey)) return;
            cleanupTaskRepository.save(ImportFileCleanupTask.builder()
                    .storageKey(storageKey)
                    .readyAt(now.plusMinutes(1))
                    .attempts(0)
                    .createdAt(now)
                    .build());
        });
    }

    private void deferTask(CleanupTask task, LocalDateTime now) {
        int nextAttempt = task.attempts() + 1;
        long delayMinutes = 1L << Math.min(nextAttempt, 8);
        transactionTemplate.executeWithoutResult(status -> jdbc.update("""
                UPDATE import_file_cleanup_tasks
                SET attempts = attempts + 1, ready_at = ?
                WHERE id = ?
                """, now.plusMinutes(delayMinutes), task.id()));
    }

    private record CleanupTask(UUID id, String storageKey, int attempts) { }
}
