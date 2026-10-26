package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportInventory;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportModeKey;
import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSelection;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.NormalizedHistoryPlan;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.service.ImportModeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Component
public class ImportWorker {
    private static final Logger log = LoggerFactory.getLogger(ImportWorker.class);
    private static final int MAX_BATCH_SIZE = 200;
    private static final int DEFAULT_MAX_JOBS = 10;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final Duration FAILURE_BACKOFF = Duration.ofMinutes(1);
    private static final String CLAIM_SQL = """
            WITH candidates AS (
                SELECT id FROM import_jobs
                WHERE state IN ('ANALYZING', 'RESOLVING', 'IMPORTING')
                  AND work_pending = true AND next_run_at <= ?
                  AND (lease_until IS NULL OR lease_until <= ?)
                  AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY created_at ASC, id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE import_jobs job
            SET lease_token = gen_random_uuid(), lease_until = ?, updated_at = ?, version = version + 1
            FROM candidates
            WHERE job.id = candidates.id
            RETURNING job.id, job.user_id, job.preview_version, job.selection_json,
                job.source_namespace, job.input_kind, job.source_format, job.storage_key,
                job.timezone, job.state, job.work_phase, job.checkpoint, job.lease_token
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ImportItemRepository itemRepository;
    private final ImportUnitTransaction unitTransaction;
    private final ImportModeRegistry modeRegistry;
    private final PlatformContentResolver contentResolver;
    private final LetterboxdHistoryNormalizer letterboxdHistoryNormalizer;
    private final TraktHistoryNormalizer traktHistoryNormalizer;
    private final DeferredOpinionReplayStager deferredOpinionReplayStager;
    private final WatchScopeSnapshotPreparer watchScopeSnapshotPreparer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ImportLimits importLimits;
    private final boolean enabled;

    public ImportWorker(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                        ImportItemRepository itemRepository, ImportUnitTransaction unitTransaction,
                        ImportModeRegistry modeRegistry, PlatformContentResolver contentResolver,
                        LetterboxdHistoryNormalizer letterboxdHistoryNormalizer,
                        TraktHistoryNormalizer traktHistoryNormalizer,
                        DeferredOpinionReplayStager deferredOpinionReplayStager,
                        WatchScopeSnapshotPreparer watchScopeSnapshotPreparer,
                        ObjectMapper objectMapper, Clock clock,
                        ImportLimits importLimits,
                        @Value("${app.import.worker.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.itemRepository = itemRepository;
        this.unitTransaction = unitTransaction;
        this.modeRegistry = modeRegistry;
        this.contentResolver = contentResolver;
        this.letterboxdHistoryNormalizer = letterboxdHistoryNormalizer;
        this.traktHistoryNormalizer = traktHistoryNormalizer;
        this.deferredOpinionReplayStager = deferredOpinionReplayStager;
        this.watchScopeSnapshotPreparer = watchScopeSnapshotPreparer;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.importLimits = importLimits;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.import.worker.fixed-delay-ms:1000}")
    public void runScheduled() {
        if (!enabled) return;
        try {
            runAvailable(DEFAULT_MAX_JOBS);
        } catch (RuntimeException exception) {
            log.error("Import worker pass failed", exception);
        }
    }

    public int runAvailable(int maxJobs) {
        if (maxJobs < 0 || maxJobs > 200) {
            throw new IllegalArgumentException("Import worker batch size must be between zero and 200");
        }
        if (maxJobs == 0) return 0;
        List<ClaimedJob> jobs = claim(maxJobs);
        for (ClaimedJob job : jobs) {
            ImportExecutionContext context = context(job);
            try {
                process(job, context);
            } catch (ImportUnitTransaction.WaitForOtherJobException exception) {
                unitTransaction.scheduleRetry(context, FAILURE_BACKOFF, false);
            } catch (RecordLimitExceededException exception) {
                unitTransaction.failDeterministically(context);
                log.warn("Import job {} exceeded its deterministic record limit", job.id());
            } catch (RuntimeException exception) {
                boolean terminal = !"RESOLVE".equals(job.workPhase());
                unitTransaction.scheduleRetry(context, FAILURE_BACKOFF, terminal);
                log.warn("Import job processing failed for job {}", job.id(), exception);
            }
        }
        return jobs.size();
    }

    private List<ClaimedJob> claim(int maxJobs) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime leaseUntil = now.plus(LEASE_DURATION);
        return transactionTemplate.execute(status -> jdbc.query(CLAIM_SQL, (result, row) -> new ClaimedJob(
                        result.getObject("id", UUID.class),
                        result.getObject("user_id", UUID.class),
                        result.getLong("preview_version"),
                        result.getString("selection_json"),
                        result.getString("source_namespace"),
                        ImportInputKind.valueOf(result.getString("input_kind")),
                        com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat
                                .valueOf(result.getString("source_format")),
                        result.getString("storage_key"),
                        result.getString("timezone"),
                        ImportJobState.valueOf(result.getString("state")),
                        result.getString("work_phase"),
                        result.getString("checkpoint"),
                        result.getObject("lease_token", UUID.class)),
                now, now, now, maxJobs, leaseUntil, now));
    }

    private ImportExecutionContext context(ClaimedJob job) {
        return new ImportExecutionContext(job.ownerId(), job.id(), job.previewVersion(),
                readPlanGeneration(job.selectionJson()), job.sourceNamespace(), job.leaseToken());
    }

    private void process(ClaimedJob job, ImportExecutionContext context) {
        String phase = job.workPhase();
        if ("INSPECT".equals(phase)) {
            inspect(job, context);
        } else if ("NORMALIZE".equals(phase)) {
            normalize(job, context);
        } else if ("RESOLVE".equals(phase)) {
            resolve(job, context);
        } else if ("APPLY".equals(phase) && job.state() == ImportJobState.IMPORTING) {
            apply(job, context);
        } else {
            unitTransaction.scheduleRetry(context, FAILURE_BACKOFF, true);
        }
    }

    private void inspect(ClaimedJob job, ImportExecutionContext context) {
        ImportInventory inventory = modeRegistry.inspect(modeKey(job), readContext(job));
        unitTransaction.finishInspection(context, inventory);
    }

    private void normalize(ClaimedJob job, ImportExecutionContext context) {
        ImportSelection selection = selection(job.selectionJson());
        String parserVersion = parserVersion(job.sourceFormat());
        AtomicLong lastOrdinal = new AtomicLong(normalizeCheckpoint(job.checkpoint()));
        AtomicLong emittedOrdinal = new AtomicLong();
        AtomicLong acceptedCount = new AtomicLong(itemRepository.countByJobId(job.id()));
        List<NormalizedImportRecord> batch = new ArrayList<>(MAX_BATCH_SIZE);
        Consumer<NormalizedImportRecord> collector = record -> {
            long globalOrdinal = emittedOrdinal.getAndIncrement();
            if (globalOrdinal > Integer.MAX_VALUE) {
                if (!batch.isEmpty()) {
                    persistNormalized(context, batch, lastOrdinal, parserVersion);
                    batch.clear();
                }
                throw new RecordLimitExceededException();
            }
            if (globalOrdinal <= lastOrdinal.get()) return;
            if (!context.sourceNamespace().equals(record.sourceKey().namespace())) {
                throw new IllegalStateException("Normalized import source namespace does not match its job");
            }
            if (acceptedCount.get() >= importLimits.maxRecords()) {
                if (!batch.isEmpty()) {
                    persistNormalized(context, batch, lastOrdinal, parserVersion);
                    batch.clear();
                }
                throw new RecordLimitExceededException();
            }
            acceptedCount.incrementAndGet();
            batch.add(new NormalizedImportRecord(record.itemId(), record.kind(), record.sourceKey(),
                    record.parentSourceId(), record.target(), record.payload(), Math.toIntExact(globalOrdinal)));
            if (batch.size() == MAX_BATCH_SIZE) {
                persistNormalized(context, batch, lastOrdinal, parserVersion);
                batch.clear();
            }
        };
        ImportReadContext readContext = readContext(job);
        modeRegistry.normalize(modeKey(job), readContext, selection, collector);
        if (!batch.isEmpty()) persistNormalized(context, batch, lastOrdinal, parserVersion);
        unitTransaction.finishNormalization(context);
    }

    private void persistNormalized(ImportExecutionContext context, List<NormalizedImportRecord> batch,
                                   AtomicLong lastOrdinal, String parserVersion) {
        List<NormalizedImportRecord> immutable = List.copyOf(batch);
        if (!unitTransaction.saveNormalized(context, immutable, parserVersion)) {
            throw new StaleImportExecutionException();
        }
        lastOrdinal.set(immutable.getLast().ordinal());
    }

    private void resolve(ClaimedJob job, ImportExecutionContext context) {
        boolean unavailable = false;
        while (true) {
            var pendingPage = itemRepository.findResolutionPending(
                    job.id(), context.planGeneration(), PageRequest.of(0, MAX_BATCH_SIZE));
            if (pendingPage.isEmpty()) break;
            List<PlatformContentResolver.ResolutionResult> results =
                    contentResolver.resolve(context, pendingPage.getContent());
            unavailable |= results.stream().anyMatch(PlatformContentResolver.ResolutionResult::unavailable);
            if (unavailable) break;
        }

        if (!unavailable) markCollectionLengthIssues(context);

        ImportSelection selection = selection(job.selectionJson());
        ImportUnitTransaction.ResolutionState state;
        if (unavailable) {
            state = ImportUnitTransaction.ResolutionState.RETRY;
        } else if (hasWaitingForUser(context)) {
            state = ImportUnitTransaction.ResolutionState.WAITING_FOR_USER;
        } else {
            NormalizedHistoryPlan plan = normalizeHistory(job, context, selection);
            state = plan.issueCount() > 0
                    ? ImportUnitTransaction.ResolutionState.WAITING_FOR_USER
                    : ImportUnitTransaction.ResolutionState.READY;
        }
        if (state != ImportUnitTransaction.ResolutionState.RETRY && selection.history()
                && job.inputKind() == ImportInputKind.PLATFORM_ARCHIVE
                && (job.sourceFormat() == ImportSourceFormat.TRAKT_ZIP
                || job.sourceFormat() == ImportSourceFormat.LETTERBOXD_ZIP)) {
            stageDeferredOpinionReplays(context);
            if (prepareWatchScopeSnapshots(context)) {
                state = ImportUnitTransaction.ResolutionState.WAITING_FOR_USER;
            }
        }
        unitTransaction.settleResolution(context, state);
    }

    private void stageDeferredOpinionReplays(ImportExecutionContext context) {
        List<ImportRecordKind> relevantKinds = List.of(ImportRecordKind.DIARY_EVENT,
                ImportRecordKind.SERIES_EXPANSION, ImportRecordKind.WATCHED_SUMMARY,
                ImportRecordKind.WATCHED_MARKER, ImportRecordKind.REVIEW,
                ImportRecordKind.CURRENT_RATING);
        List<String> relevantOutcomes = List.of("MATCHED", "RESOLVED", "EXPANSION_PREPARED",
                "AUXILIARY_EVIDENCE", "NORMALIZATION_ISSUE", "AMBIGUOUS", "NOT_FOUND");
        java.util.Set<String> contentKeys = new java.util.LinkedHashSet<>();
        java.util.Set<com.watchwise.watchwise_api.platformimport.model.ImportSourceKey> matchingScopeSources =
                new java.util.LinkedHashSet<>();
        long afterOrdinal = -1;
        UUID afterId = new UUID(0, 0);
        while (true) {
            List<ImportItem> page = itemRepository.findHistoryRowsAfter(context.jobId(), context.ownerId(),
                    context.planGeneration(), relevantKinds, relevantOutcomes, afterOrdinal, afterId,
                    PageRequest.of(0, MAX_BATCH_SIZE));
            if (page.isEmpty()) break;
            for (ImportItem item : page) {
                afterOrdinal = item.getOrdinal();
                afterId = item.getId();
                if (item.getDecision() == com.watchwise.watchwise_api.platformimport.model.ImportDecision.SKIP_ITEM) {
                    continue;
                }
                boolean matchingSourceIdentity = item.getNormalizedKind() == ImportRecordKind.WATCHED_SUMMARY
                        || item.getNormalizedKind() == ImportRecordKind.WATCHED_MARKER
                        || item.getNormalizedKind() == ImportRecordKind.REVIEW
                        || item.getNormalizedKind() == ImportRecordKind.CURRENT_RATING;
                if (matchingSourceIdentity) {
                    matchingScopeSources.add(new com.watchwise.watchwise_api.platformimport.model.ImportSourceKey(
                            item.getSourceNamespace(), item.getObjectKind(), item.getSourceId()));
                }
                if (item.getResolvedContentKey() == null) continue;
                boolean detailedEvidence = item.getNormalizedKind() == ImportRecordKind.DIARY_EVENT
                        || item.getNormalizedKind() == ImportRecordKind.SERIES_EXPANSION;
                boolean explicitlyConfirmedScope = item.getDecision()
                        == com.watchwise.watchwise_api.platformimport.model.ImportDecision.CONFIRM_WATCH_SCOPE;
                boolean usableDetailedEvidence = detailedEvidence
                        && !"NORMALIZATION_ISSUE".equals(item.getOutcome());
                if (usableDetailedEvidence || explicitlyConfirmedScope) {
                    contentKeys.add(item.getResolvedContentKey());
                }
            }
            if (page.size() < MAX_BATCH_SIZE) break;
        }
        if (contentKeys.isEmpty() && matchingScopeSources.isEmpty()) return;

        AtomicLong acceptedCount = new AtomicLong(
                itemRepository.countByJobIdAndPlanGeneration(context.jobId(), context.planGeneration()));
        List<NormalizedImportRecord> batch = new ArrayList<>(MAX_BATCH_SIZE);
        Consumer<NormalizedImportRecord> collector = record -> {
            batch.add(record);
            if (batch.size() == MAX_BATCH_SIZE) {
                acceptedCount.addAndGet(persistDeferredReplayBatch(context, batch));
                batch.clear();
                if (acceptedCount.get() > importLimits.maxRecords()) throw new RecordLimitExceededException();
            }
        };
        deferredOpinionReplayStager.emit(context.ownerId(), context.sourceNamespace(), contentKeys,
                matchingScopeSources, collector);
        if (!batch.isEmpty()) acceptedCount.addAndGet(persistDeferredReplayBatch(context, batch));
        if (acceptedCount.get() > importLimits.maxRecords()) throw new RecordLimitExceededException();
    }

    private int persistDeferredReplayBatch(ImportExecutionContext context, List<NormalizedImportRecord> records) {
        int inserted = unitTransaction.stageDeferredOpinionReplays(context, List.copyOf(records));
        if (inserted < 0) throw new StaleImportExecutionException();
        return inserted;
    }

    private boolean prepareWatchScopeSnapshots(ImportExecutionContext context) {
        List<ImportRecordKind> kinds = List.of(ImportRecordKind.WATCHED_SUMMARY,
                ImportRecordKind.WATCHED_MARKER, ImportRecordKind.REVIEW, ImportRecordKind.CURRENT_RATING);
        List<String> outcomes = List.of("MATCHED", "RESOLVED", "AUXILIARY_EVIDENCE");
        Map<com.watchwise.watchwise_api.content.dto.MediaReference, WatchScopeSnapshotPreparer.LoadedScope> cache =
                new java.util.HashMap<>();
        long afterOrdinal = -1;
        UUID afterId = new UUID(0, 0);
        boolean waitingForUser = false;
        while (true) {
            List<ImportItem> page = itemRepository.findHistoryRowsAfter(context.jobId(), context.ownerId(),
                    context.planGeneration(), kinds, outcomes, afterOrdinal, afterId,
                    PageRequest.of(0, MAX_BATCH_SIZE));
            if (page.isEmpty()) break;
            List<ImportUnitTransaction.WatchScopePreparation> prepared = new ArrayList<>();
            for (ImportItem item : page) {
                afterOrdinal = item.getOrdinal();
                afterId = item.getId();
                if (item.getDecision() == com.watchwise.watchwise_api.platformimport.model.ImportDecision.SKIP_ITEM) {
                    continue;
                }
                WatchScopeSnapshotPreparer.Prepared result =
                        watchScopeSnapshotPreparer.prepare(context.ownerId(), item, cache);
                if (result == null) continue;
                prepared.add(new ImportUnitTransaction.WatchScopePreparation(result.itemId(), result.payloadJson(),
                        result.outcome(), result.issue()));
                waitingForUser |= result.waitingForUser();
            }
            if (!prepared.isEmpty() && !unitTransaction.saveWatchScopePreparations(context, prepared)) {
                throw new StaleImportExecutionException();
            }
            if (page.size() < MAX_BATCH_SIZE) break;
        }
        return waitingForUser;
    }

    private void markCollectionLengthIssues(ImportExecutionContext context) {
        for (String kind : List.of("LIST_METADATA", "LIST_ITEM")) {
            long afterOrdinal = -1;
            UUID afterId = new UUID(0, 0);
            while (true) {
                List<ImportItem> page = itemRepository.findItemsOfKindAfter(context.jobId(),
                        context.planGeneration(), kind, afterOrdinal, afterId.toString(), MAX_BATCH_SIZE);
                if (page.isEmpty()) break;
                afterOrdinal = page.getLast().getOrdinal();
                afterId = page.getLast().getId();
                for (ImportItem item : page) {
                    if (item.getDecision() == com.watchwise.watchwise_api.platformimport.model.ImportDecision.SKIP_ITEM) {
                        continue;
                    }
                    String issue = collectionLengthIssue(item);
                    boolean collectionIssue = item.getIssue() != null
                            && (item.getIssue().startsWith("LIST_ITEM_DESCRIPTION_TOO_LONG")
                            || item.getIssue().startsWith("LIST_METADATA_FIELDS_TOO_LONG"));
                    if (issue != null) {
                        item.setOutcome("NORMALIZATION_ISSUE");
                        item.setIssue(issue);
                    } else if (collectionIssue && "NORMALIZATION_ISSUE".equals(item.getOutcome())) {
                        item.setOutcome("RESOLVED");
                        item.setIssue(null);
                    }
                }
                itemRepository.saveAll(page);
            }
        }
    }

    private String collectionLengthIssue(ImportItem item) {
        try {
            JsonNode root = objectMapper.readTree(item.getPayloadJson());
            JsonNode source = root.path("source").isObject() ? root.path("source") : root;
            JsonNode fields = root.path("resolution").path("fields");
            JsonNode titleValue = fields.path("TITLE").isTextual() ? fields.path("TITLE") : source.path("title");
            JsonNode descriptionValue = fields.path("DESCRIPTION").isTextual()
                    ? fields.path("DESCRIPTION") : source.path("description");
            String title = titleValue.isTextual() ? titleValue.asString() : null;
            String description = descriptionValue.isTextual() ? descriptionValue.asString() : null;
            if (item.getNormalizedKind() == ImportRecordKind.LIST_ITEM) {
                return description != null && description.length() > 400
                        ? "LIST_ITEM_DESCRIPTION_TOO_LONG: Edit the note to 400 characters or fewer." : null;
            }
            if (title != null && title.length() > 255 || description != null && description.length() > 400) {
                return "LIST_METADATA_FIELDS_TOO_LONG: Edit the name or description to fit the limits.";
            }
            return null;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Collection item payload is invalid", exception);
        }
    }

    private NormalizedHistoryPlan normalizeHistory(ClaimedJob job, ImportExecutionContext context,
                                                   ImportSelection selection) {
        if (!selection.history()) return NormalizedHistoryPlan.empty(job.id());
        return switch (job.sourceFormat()) {
            case TRAKT_ZIP, TRAKT_JSON -> traktHistoryNormalizer.normalize(context);
            case LETTERBOXD_ZIP, LETTERBOXD_CSV ->
                    letterboxdHistoryNormalizer.normalize(context, selection.history());
            case WATCHWISE_TEMPLATE_JSON -> NormalizedHistoryPlan.empty(job.id());
        };
    }

    private String parserVersion(ImportSourceFormat sourceFormat) {
        return switch (sourceFormat) {
            case TRAKT_ZIP, TRAKT_JSON -> "trakt-history-v1";
            case LETTERBOXD_ZIP, LETTERBOXD_CSV -> "letterboxd-history-v1";
            case WATCHWISE_TEMPLATE_JSON -> "watchwise-template-v1";
        };
    }

    private boolean hasWaitingForUser(ImportExecutionContext context) {
        long afterOrdinal = -1;
        UUID afterId = new UUID(0, 0);
        while (true) {
            List<ImportItem> page = itemRepository.findWaitingItemsAfter(context.jobId(), context.ownerId(),
                    context.planGeneration(), afterOrdinal, afterId, PageRequest.of(0, MAX_BATCH_SIZE));
            for (ImportItem item : page) {
                afterOrdinal = item.getOrdinal();
                afterId = item.getId();
                if ("NORMALIZATION_ISSUE".equals(item.getOutcome())
                        && item.getDecision() != com.watchwise.watchwise_api.platformimport.model.ImportDecision.SKIP_ITEM
                        && item.getDecision() != com.watchwise.watchwise_api.platformimport.model.ImportDecision.RESOLVE_TARGET) {
                    return true;
                }
                if (isCandidateEnvelope(item.getResolvedTargetJson())
                        && item.getDecision() != com.watchwise.watchwise_api.platformimport.model.ImportDecision.RESOLVE_TARGET
                        && item.getDecision() != com.watchwise.watchwise_api.platformimport.model.ImportDecision.SKIP_ITEM) {
                    return true;
                }
            }
            if (page.size() < MAX_BATCH_SIZE) return false;
        }
    }

    private void apply(ClaimedJob job, ImportExecutionContext context) {
        List<ImportItem> units = itemRepository.findApplyPending(
                job.id(), context.planGeneration(), PageRequest.of(0, MAX_BATCH_SIZE));
        for (ImportItem item : units) {
            if (!unitTransaction.apply(context, item.getId())) return;
        }
        boolean moreUnits = itemRepository.existsApplyPending(job.id(), context.planGeneration());
        unitTransaction.finishApplyChunk(context, moreUnits);
    }

    private boolean isCandidateEnvelope(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            JsonNode node = objectMapper.readTree(value);
            return node.isObject() && node.has("schemaVersion") && node.has("candidates");
        } catch (JacksonException exception) {
            return false;
        }
    }

    private ImportModeKey modeKey(ClaimedJob job) {
        return new ImportModeKey(job.inputKind(), job.sourceFormat());
    }

    private ImportReadContext readContext(ClaimedJob job) {
        return new ImportReadContext(job.ownerId(), job.id(), job.storageKey(), job.sourceNamespace(),
                java.time.ZoneId.of(job.timezone()));
    }

    private ImportSelection selection(String selectionJson) {
        try {
            JsonNode selectionNode = objectMapper.readTree(selectionJson).path("selection");
            if (!selectionNode.isObject()) return new ImportSelection(false, false, java.util.Set.of(), null);
            return objectMapper.treeToValue(selectionNode, ImportSelection.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Stored import selection is invalid", exception);
        }
    }

    private long normalizeCheckpoint(String checkpoint) {
        if (checkpoint == null || !checkpoint.startsWith("NORMALIZE:")) return -1;
        try {
            return Long.parseLong(checkpoint.substring("NORMALIZE:".length()));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Stored import normalization checkpoint is invalid", exception);
        }
    }

    private long readPlanGeneration(String selectionJson) {
        if (selectionJson == null || selectionJson.isBlank()) return 0;
        try {
            JsonNode root = objectMapper.readTree(selectionJson);
            JsonNode version = root.get("schemaVersion");
            JsonNode generation = root.get("planGeneration");
            if (version == null && generation == null) return 0;
            if (version == null || !version.isInt() || version.asInt() != 1
                    || generation == null || !generation.canConvertToLong() || generation.asLong() < 0) {
                throw new IllegalStateException("Stored import plan version is invalid");
            }
            return generation.asLong();
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored import plan is invalid", exception);
        }
    }

    private record ClaimedJob(UUID id, UUID ownerId, long previewVersion, String selectionJson,
                              String sourceNamespace, ImportInputKind inputKind,
                              com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat sourceFormat,
                              String storageKey, String timezone, ImportJobState state,
                              String workPhase, String checkpoint, UUID leaseToken) { }

    private static final class RecordLimitExceededException extends RuntimeException { }

    private static final class StaleImportExecutionException extends RuntimeException { }
}
