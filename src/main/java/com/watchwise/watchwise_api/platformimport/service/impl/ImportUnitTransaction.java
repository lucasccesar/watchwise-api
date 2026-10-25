package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.ImportInventory;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.SourceApplicationDecision;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.platformimport.service.ImportUnitApplier;
import com.watchwise.watchwise_api.platformimport.service.ImportUnitTerminalListener;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ImportUnitTransaction {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final int NORMALIZE_BATCH_SIZE = 200;
    private static final int COMPACTION_BATCH_SIZE = 200;

    private final ImportJobRepository jobRepository;
    private final ImportItemRepository itemRepository;
    private final UserRepository userRepository;
    private final ImportLedger ledger;
    private final ImportEventDeduplicator eventDeduplicator;
    private final List<ImportUnitApplier> appliers;
    private final List<ImportUnitTerminalListener> terminalListeners;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ImportUnitTransaction(ImportJobRepository jobRepository, ImportItemRepository itemRepository,
                                 UserRepository userRepository, ImportLedger ledger,
                                 ImportEventDeduplicator eventDeduplicator,
                                 List<ImportUnitApplier> appliers,
                                 List<ImportUnitTerminalListener> terminalListeners,
                                 ObjectMapper objectMapper, Clock clock) {
        this.jobRepository = jobRepository;
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
        this.ledger = ledger;
        this.eventDeduplicator = eventDeduplicator;
        this.appliers = List.copyOf(appliers);
        this.terminalListeners = List.copyOf(terminalListeners);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean apply(ImportExecutionContext context, UUID unitId) {
        ImportJob job = lockRunnableJob(context, ImportJobState.IMPORTING);
        if (job == null) return false;
        ImportItem item = itemRepository.findById(unitId).orElse(null);
        if (item == null || !item.getJobId().equals(context.jobId())
                || item.getPlanGeneration() != context.planGeneration()) return false;
        if ("APPLIED".equals(item.getOutcome()) || "SKIPPED".equals(item.getOutcome())) return true;

        ImportUnitApplier applier = applierFor(item);
        ImportSourceKey sourceKey = applier.sourceKey(context, item);
        SourceApplicationDecision sourceDecision = eventDeduplicator.decide(context.ownerId(), context.jobId(),
                sourceKey, ImportEventDeduplicator.payloadHash(item.getPayloadJson()));
        if (sourceDecision == SourceApplicationDecision.SKIP_CONSUMED) {
            item.setOutcome("SKIPPED");
            item.setIssue("SOURCE_ALREADY_CONSUMED");
            itemRepository.save(item);
            notifyTerminal(context, item);
            checkpointAfterUnit(job, item);
            return true;
        }
        if (sourceDecision == SourceApplicationDecision.WAIT_OTHER_JOB) throw new WaitForOtherJobException();
        if (sourceDecision == SourceApplicationDecision.MATCH_REQUIRED) {
            throw new IllegalStateException("Source deduplication cannot decide a content pass match");
        }

        if (item.getDecision() == ImportDecision.SKIP_ITEM) {
            ledger.markSkipped(context.ownerId(), sourceKey, null, null, "USER_SKIPPED");
            item.setOutcome("SKIPPED");
            item.setIssue(null);
        } else {
            try {
                applier.apply(context, item.getId());
            } catch (WatchEventImportApplier.PassAssignmentRequiredException exception) {
                pauseForPassAssignment(job, item, exception.getMessage(), exception.decision());
                return true;
            } catch (LetterboxdSeriesExpansionPlanner.SeriesExpansionIssueException exception) {
                pauseForExpansionIssue(job, item, exception.getMessage());
                return true;
            }
            if (!applier.isComplete(context, item.getId())) {
                if (!"EXPANDING".equals(item.getOutcome())) item.setOutcome("MATCHED");
                itemRepository.save(item);
                job.setCheckpoint(Long.toString(item.getOrdinal()));
                renew(job);
                return true;
            }
            if (!ledger.isConsumed(context.ownerId(), sourceKey)) {
                throw new IllegalStateException("Import unit did not commit its source ledger transition");
            }
            if (!"SKIPPED".equals(item.getOutcome())) {
                item.setOutcome("APPLIED");
                item.setIssue(null);
            }
        }

        itemRepository.save(item);
        notifyTerminal(context, item);
        checkpointAfterUnit(job, item);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean saveNormalized(ImportExecutionContext context, List<NormalizedImportRecord> records,
                                  String parserVersion) {
        if (records == null || records.isEmpty()) return true;
        if (records.size() > 200) {
            throw new IllegalArgumentException("Normalized import batches are limited to 200 records");
        }
        if (parserVersion == null || parserVersion.isBlank()) {
            throw new IllegalArgumentException("Import parser version is required");
        }
        ImportJob job = lockRunnableJob(context, ImportJobState.ANALYZING);
        if (job == null || !"NORMALIZE".equals(job.getWorkPhase())) return false;
        List<ImportItem> items = records.stream().map(record -> normalizedItem(context, record, parserVersion)).toList();
        itemRepository.saveAll(items);
        job.setCheckpoint("NORMALIZE:" + records.getLast().ordinal());
        renew(job);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finishInspection(ImportExecutionContext context, ImportInventory inventory) {
        ImportJob job = lockRunnableJob(context, ImportJobState.ANALYZING);
        if (job == null || !"INSPECT".equals(job.getWorkPhase())) return false;
        job.setCheckpoint(writeJson(inventory));
        job.setState(ImportJobState.AWAITING_SELECTION);
        job.setWorkPhase(null);
        job.setWorkPending(false);
        job.setNextRunAt(null);
        releaseLease(job);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finishNormalization(ImportExecutionContext context) {
        ImportJob job = lockRunnableJob(context, ImportJobState.ANALYZING);
        if (job == null || !"NORMALIZE".equals(job.getWorkPhase())) return false;
        job.setState(ImportJobState.RESOLVING);
        job.setWorkPhase("RESOLVE");
        job.setWorkPending(true);
        job.setNextRunAt(now().plusSeconds(1));
        releaseLease(job);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean saveResolution(ImportExecutionContext context, List<ResolutionUpdate> updates) {
        if (updates == null || updates.isEmpty()) return true;
        if (updates.size() > 200 || updates.stream().map(ResolutionUpdate::itemId).distinct().count() != updates.size()) {
            throw new IllegalArgumentException("Resolution updates must contain at most 200 unique items");
        }
        ImportJob job = lockRunnableJob(context, ImportJobState.RESOLVING);
        if (job == null || !"RESOLVE".equals(job.getWorkPhase())) return false;
        java.util.Map<UUID, ResolutionUpdate> updatesById = new java.util.LinkedHashMap<>();
        for (ResolutionUpdate update : updates) {
            updatesById.put(update.itemId(), update);
        }
        List<ImportItem> changedItems = itemRepository.findAllByJobIdAndPlanGenerationAndIdIn(
                context.jobId(), context.planGeneration(), updatesById.keySet());
        if (changedItems.size() != updates.size()) {
            throw new IllegalArgumentException("Resolution update item is outside its job generation");
        }
        for (ImportItem item : changedItems) {
            ResolutionUpdate update = updatesById.get(item.getId());
            item.setOutcome(update.outcome());
            item.setResolvedTargetJson(update.resolvedTargetJson());
            item.setNormalizedContentKey(update.normalizedContentKey());
            item.setResolvedContentKey(update.resolvedContentKey());
            item.setIssue(update.issue());
        }
        itemRepository.saveAll(changedItems);
        renew(job);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renewHistoryNormalizationLease(ImportExecutionContext context) {
        ImportJob job = lockRunnableJob(context, ImportJobState.RESOLVING);
        if (job == null || !"RESOLVE".equals(job.getWorkPhase())) return false;
        renew(job);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean saveHistoryNormalization(ImportExecutionContext context,
                                            List<HistoryNormalizationUpdate> updates) {
        if (updates == null || updates.isEmpty()) return true;
        if (updates.size() > 200 || updates.stream().map(HistoryNormalizationUpdate::itemId).distinct().count()
                != updates.size()) {
            throw new IllegalArgumentException("History normalization updates must contain at most 200 unique items");
        }
        ImportJob job = lockRunnableJob(context, ImportJobState.RESOLVING);
        if (job == null || !"RESOLVE".equals(job.getWorkPhase())) return false;

        java.util.Map<UUID, HistoryNormalizationUpdate> updatesById = new java.util.LinkedHashMap<>();
        for (HistoryNormalizationUpdate update : updates) updatesById.put(update.itemId(), update);
        List<ImportItem> changedItems = itemRepository.findAllByJobIdAndPlanGenerationAndIdIn(
                context.jobId(), context.planGeneration(), updatesById.keySet());
        if (changedItems.size() != updates.size()) {
            throw new IllegalArgumentException("History normalization item is outside its job generation");
        }
        for (ImportItem item : changedItems) {
            HistoryNormalizationUpdate update = updatesById.get(item.getId());
            item.setNormalizedKind(update.normalizedKind());
            item.setPayloadJson(update.payloadJson());
            item.setResolvedTargetJson(update.resolvedTargetJson());
            item.setResolvedContentKey(update.resolvedContentKey());
            item.setOutcome(update.outcome());
            item.setIssue(update.issue());
        }
        itemRepository.saveAll(changedItems);
        renew(job);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean settleResolution(ImportExecutionContext context, ResolutionState state) {
        ImportJob job = lockRunnableJob(context, ImportJobState.RESOLVING);
        if (job == null || !"RESOLVE".equals(job.getWorkPhase())) return false;
        if (state == ResolutionState.RETRY) {
            job.setWorkPending(true);
            job.setNextRunAt(now().plus(state.backoff()));
        } else if (state == ResolutionState.CONTINUE) {
            job.setWorkPending(true);
            job.setNextRunAt(now());
        } else if (state == ResolutionState.WAITING_FOR_USER) {
            job.setWorkPending(false);
            job.setNextRunAt(null);
        } else {
            job.setState(ImportJobState.READY);
            job.setWorkPhase("APPLY");
            job.setWorkPending(false);
            job.setNextRunAt(null);
        }
        releaseLease(job);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finishApplyChunk(ImportExecutionContext context, boolean moreUnits) {
        ImportJob job = lockRunnableJob(context, ImportJobState.IMPORTING);
        if (job == null || !"APPLY".equals(job.getWorkPhase())) return false;
        if (moreUnits) {
            job.setWorkPending(true);
            job.setNextRunAt(now());
            releaseLease(job);
            return true;
        }
        itemRepository.compactAppliedPayloads(context.jobId(), context.planGeneration(), COMPACTION_BATCH_SIZE);
        if (itemRepository.existsAppliedPayloads(context.jobId(), context.planGeneration())) {
            job.setWorkPending(true);
            job.setNextRunAt(now());
            releaseLease(job);
            return true;
        }
        job.setState(ImportJobState.COMPLETED);
        job.setWorkPending(false);
        job.setNextRunAt(null);
        job.setCheckpoint(job.getCheckpoint() == null ? "COMPLETED" : job.getCheckpoint());
        releaseLease(job);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean scheduleRetry(ImportExecutionContext context, Duration delay, boolean terminal) {
        ImportJob job = lockRunnableJob(context, null);
        if (job == null) return false;
        if (terminal) {
            job.setState(ImportJobState.FAILED);
            job.setWorkPending(false);
            job.setNextRunAt(null);
        } else {
            job.setWorkPending(true);
            job.setNextRunAt(now().plus(delay));
        }
        releaseLease(job);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failDeterministically(ImportExecutionContext context) {
        ImportJob job = lockRunnableJob(context, ImportJobState.ANALYZING);
        if (job == null || !"NORMALIZE".equals(job.getWorkPhase())) return false;
        job.setState(ImportJobState.FAILED);
        job.setWorkPhase(null);
        job.setWorkPending(false);
        job.setNextRunAt(null);
        job.setUpdatedAt(now());
        releaseLease(job);
        return true;
    }

    private ImportJob lockRunnableJob(ImportExecutionContext context, ImportJobState expectedState) {
        ImportJob job = jobRepository.findByIdForUpdate(context.jobId()).orElse(null);
        LocalDateTime now = now();
        if (job == null || !job.getUserId().equals(context.ownerId())
                || !userRepository.existsById(context.ownerId())
                || (expectedState != null && job.getState() != expectedState)
                || !job.isWorkPending()
                || !context.leaseToken().equals(job.getLeaseToken())
                || job.getLeaseUntil() == null || !job.getLeaseUntil().isAfter(now)
                || job.getPreviewVersion() != context.previewVersion()
                || readPlanGeneration(job.getSelectionJson()) != context.planGeneration()) {
            return null;
        }
        return job;
    }

    private ImportItem normalizedItem(ImportExecutionContext context, NormalizedImportRecord record,
                                     String parserVersion) {
        UUID itemId = uuidForItem(context, record.itemId());
        ImportItem item = ImportItem.builder()
                .id(itemId)
                .jobId(context.jobId())
                .planGeneration(context.planGeneration())
                .normalizedKind(record.kind())
                .sourceNamespace(record.sourceKey().namespace())
                .objectKind(record.sourceKey().objectKind())
                .sourceId(record.sourceKey().sourceId())
                .sourceEventKey(record.sourceKey().sourceId())
                .parentSourceId(record.parentSourceId())
                .parserVersion(parserVersion)
                .payloadJson(writeJson(record.payload()))
                .resolvedTargetJson(record.target() == null ? null : writeJson(record.target()))
                .ordinal(record.ordinal())
                .build();
        return item;
    }

    private UUID uuidForItem(ImportExecutionContext context, String itemId) {
        String seed = context.jobId() + ":" + context.planGeneration() + ":" + itemId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private ImportUnitApplier applierFor(ImportItem item) {
        List<ImportUnitApplier> matches = appliers.stream()
                .filter(applier -> applier.supports(item.getNormalizedKind())).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("Import unit must have exactly one applier");
        }
        return matches.getFirst();
    }

    private void notifyTerminal(ImportExecutionContext context, ImportItem item) {
        for (ImportUnitTerminalListener listener : terminalListeners) listener.onTerminal(context, item);
    }

    private void checkpointAfterUnit(ImportJob job, ImportItem item) {
        job.setCheckpoint(Long.toString(item.getOrdinal()));
        if (job.getState() == ImportJobState.IMPORTING && job.isWorkPending()) renew(job);
        else releaseLease(job);
    }

    private void pauseForPassAssignment(ImportJob job, ImportItem item, String issue,
                                       SourceApplicationDecision decision) {
        if (decision != SourceApplicationDecision.MATCH_REQUIRED) {
            throw new IllegalArgumentException("Only a match-required decision can pause event application");
        }
        item.setOutcome("MATCHED");
        item.setIssue(issue);
        itemRepository.save(item);
        job.setState(ImportJobState.READY);
        job.setWorkPhase("APPLY");
        job.setWorkPending(false);
        job.setNextRunAt(null);
        releaseLease(job);
    }

    private void pauseForExpansionIssue(ImportJob job, ImportItem item, String issue) {
        item.setOutcome("NORMALIZATION_ISSUE");
        item.setIssue(issue);
        itemRepository.save(item);
        job.setState(ImportJobState.READY);
        job.setWorkPhase("APPLY");
        job.setWorkPending(false);
        job.setNextRunAt(null);
        releaseLease(job);
    }

    private long readPlanGeneration(String selectionJson) {
        if (selectionJson == null || selectionJson.isBlank()) return 0;
        try {
            JsonNode root = objectMapper.readTree(selectionJson);
            JsonNode generation = root.get("planGeneration");
            return generation == null ? 0 : generation.asLong(-1);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored import plan is invalid", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Import checkpoint could not be serialized", exception);
        }
    }

    private void renew(ImportJob job) {
        LocalDateTime now = now();
        job.setLeaseUntil(now.plus(LEASE_DURATION));
        job.setNextRunAt(now);
        job.setUpdatedAt(now);
    }

    private void releaseLease(ImportJob job) {
        job.setLeaseToken(null);
        job.setLeaseUntil(null);
        job.setUpdatedAt(now());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public enum ResolutionState {
        READY(Duration.ZERO), WAITING_FOR_USER(Duration.ZERO), CONTINUE(Duration.ZERO), RETRY(Duration.ofMinutes(1));

        private final Duration backoff;

        ResolutionState(Duration backoff) {
            this.backoff = backoff;
        }

        public Duration backoff() {
            return backoff;
        }
    }

    public record ResolutionUpdate(UUID itemId, String outcome, String resolvedTargetJson,
                                   String normalizedContentKey, String resolvedContentKey, String issue) { }

    public record HistoryNormalizationUpdate(UUID itemId, ImportRecordKind normalizedKind, String payloadJson,
                                             String resolvedTargetJson, String resolvedContentKey,
                                             String outcome, String issue) { }

    public static final class WaitForOtherJobException extends RuntimeException { }
}
