package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.AliasQuery;
import com.watchwise.watchwise_api.platformimport.model.AliasResolution;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportInventory;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportModeKey;
import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportSelection;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ExternalContentAliasService;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportIdentityNormalizer;
import com.watchwise.watchwise_api.platformimport.service.ImportModeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
    private final ExternalContentAliasService aliasService;
    private final ImportIdentityNormalizer identityNormalizer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean enabled;

    public ImportWorker(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                        ImportItemRepository itemRepository, ImportUnitTransaction unitTransaction,
                        ImportModeRegistry modeRegistry, ExternalContentAliasService aliasService,
                        ImportIdentityNormalizer identityNormalizer, ObjectMapper objectMapper, Clock clock,
                        @Value("${app.import.worker.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.itemRepository = itemRepository;
        this.unitTransaction = unitTransaction;
        this.modeRegistry = modeRegistry;
        this.aliasService = aliasService;
        this.identityNormalizer = identityNormalizer;
        this.objectMapper = objectMapper;
        this.clock = clock;
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
        AtomicLong lastOrdinal = new AtomicLong(normalizeCheckpoint(job.checkpoint()));
        List<NormalizedImportRecord> batch = new ArrayList<>(MAX_BATCH_SIZE);
        modeRegistry.normalize(modeKey(job), readContext(job), selection, record -> {
            if (record.ordinal() <= lastOrdinal.get()) return;
            batch.add(record);
            if (batch.size() == MAX_BATCH_SIZE) {
                persistNormalized(context, batch, lastOrdinal);
                batch.clear();
            }
        });
        if (!batch.isEmpty()) persistNormalized(context, batch, lastOrdinal);
        unitTransaction.finishNormalization(context);
    }

    private void persistNormalized(ImportExecutionContext context, List<NormalizedImportRecord> batch,
                                   AtomicLong lastOrdinal) {
        List<NormalizedImportRecord> immutable = List.copyOf(batch);
        if (!unitTransaction.saveNormalized(context, immutable)) {
            throw new StaleImportExecutionException();
        }
        lastOrdinal.set(immutable.getLast().ordinal());
    }

    private void resolve(ClaimedJob job, ImportExecutionContext context) {
        boolean unavailable = false;
        while (true) {
            Page<ImportItem> pendingPage = itemRepository.findResolutionPending(
                    job.id(), context.planGeneration(), PageRequest.of(0, MAX_BATCH_SIZE));
            if (pendingPage.isEmpty()) break;

            Map<QueryKey, List<ImportItem>> groups = new LinkedHashMap<>();
            Map<QueryKey, String> contentKeys = new LinkedHashMap<>();
            List<ImportUnitTransaction.ResolutionUpdate> directUpdates =
                    new ArrayList<>(pendingPage.getNumberOfElements());
            for (ImportItem item : pendingPage.getContent()) {
                if (item.getDecision() == com.watchwise.watchwise_api.platformimport.model.ImportDecision.SKIP_ITEM) {
                    directUpdates.add(new ImportUnitTransaction.ResolutionUpdate(item.getId(), "RESOLVED",
                            item.getResolvedTargetJson(), item.getNormalizedContentKey(), null));
                    continue;
                }
                AliasQuery query = queryFor(item);
                if (query == null) {
                    directUpdates.add(new ImportUnitTransaction.ResolutionUpdate(item.getId(),
                            item.getResolvedTargetJson() == null ? "RESOLVED" : "MATCHED",
                            item.getResolvedTargetJson(), item.getNormalizedContentKey(), null));
                    continue;
                }
                QueryKey key = key(query);
                String normalizedKey = normalizedContentKey(query);
                if (item.getResolvedTargetJson() != null && !isCandidateEnvelope(item.getResolvedTargetJson())) {
                    directUpdates.add(new ImportUnitTransaction.ResolutionUpdate(item.getId(), "MATCHED",
                            item.getResolvedTargetJson(), normalizedKey, null));
                } else {
                    groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
                    contentKeys.putIfAbsent(key, normalizedKey);
                }
            }
            List<Map.Entry<QueryKey, List<ImportItem>>> remote = new ArrayList<>();
            Map<String, ImportItem> stagedByContentKey = new LinkedHashMap<>();
            if (!contentKeys.isEmpty()) {
                for (ImportItem staged : itemRepository.findStagedResolutions(job.id(), context.planGeneration(),
                        contentKeys.values(), List.of("MATCHED", "AMBIGUOUS", "NOT_FOUND"))) {
                    stagedByContentKey.putIfAbsent(staged.getNormalizedContentKey(), staged);
                }
            }
            for (Map.Entry<QueryKey, List<ImportItem>> entry : groups.entrySet()) {
                ImportItem stagedItem = stagedByContentKey.get(contentKeys.get(entry.getKey()));
                if (stagedItem == null) {
                    remote.add(entry);
                    continue;
                }
                StageResult cached = new StageResult(stagedItem.getOutcome(), stagedItem.getResolvedTargetJson(),
                        stagedItem.getIssue());
                appendGroupUpdates(directUpdates, entry.getValue(), cached, contentKeys.get(entry.getKey()));
            }

            List<AliasQuery> queries = remote.stream()
                    .map(entry -> queryFor(entry.getValue().getFirst())).toList();
            if (!queries.isEmpty()) {
                List<AliasResolution> resolutions = aliasService.resolveBatch(job.ownerId(), queries);
                if (resolutions.size() != remote.size()) {
                    throw new IllegalStateException("TMDB resolver returned an invalid batch");
                }
                for (int index = 0; index < remote.size(); index++) {
                    Map.Entry<QueryKey, List<ImportItem>> entry = remote.get(index);
                    StageResult result = stage(queries.get(index), resolutions.get(index));
                    appendGroupUpdates(directUpdates, entry.getValue(), result, contentKeys.get(entry.getKey()));
                    unavailable |= "UNAVAILABLE".equals(result.outcome());
                }
            }
            persistResolutionUpdates(context, directUpdates);
            if (unavailable) break;
        }

        ImportUnitTransaction.ResolutionState state = unavailable
                ? ImportUnitTransaction.ResolutionState.RETRY
                : hasWaitingForUser(job.id(), context.planGeneration())
                ? ImportUnitTransaction.ResolutionState.WAITING_FOR_USER
                : ImportUnitTransaction.ResolutionState.READY;
        unitTransaction.settleResolution(context, state);
    }

    private boolean hasWaitingForUser(UUID jobId, long generation) {
        int pageNumber = 0;
        while (true) {
            List<ImportItem> page = itemRepository.findByJobIdAndPlanGenerationAndOutcomeInOrderByOrdinalAscIdAsc(
                    jobId, generation, List.of("AMBIGUOUS", "NOT_FOUND"),
                    PageRequest.of(pageNumber++, MAX_BATCH_SIZE));
            for (ImportItem item : page) {
                if (isCandidateEnvelope(item.getResolvedTargetJson())
                        && item.getDecision() != com.watchwise.watchwise_api.platformimport.model.ImportDecision.RESOLVE_TARGET
                        && item.getDecision() != com.watchwise.watchwise_api.platformimport.model.ImportDecision.SKIP_ITEM) {
                    return true;
                }
            }
            if (page.size() < MAX_BATCH_SIZE) return false;
        }
    }

    private void appendGroupUpdates(List<ImportUnitTransaction.ResolutionUpdate> updates,
                                    List<ImportItem> items, StageResult result, String normalizedContentKey) {
        for (ImportItem item : items) {
            updates.add(new ImportUnitTransaction.ResolutionUpdate(item.getId(), result.outcome(),
                    result.resolvedTargetJson(), normalizedContentKey, result.issue()));
        }
    }

    private void persistResolutionUpdates(ImportExecutionContext context,
                                          List<ImportUnitTransaction.ResolutionUpdate> updates) {
        if (updates.isEmpty()) return;
        if (!unitTransaction.saveResolution(context, List.copyOf(updates))) {
            throw new StaleImportExecutionException();
        }
    }

    private StageResult stage(AliasQuery query, AliasResolution resolution) {
        if (resolution.status() == AliasResolution.Status.MATCHED) {
            return new StageResult("MATCHED", writeJson(resolution.candidates().getFirst()), null);
        }
        String status = resolution.status().name();
        return new StageResult(status, candidateEnvelope(query, resolution.candidates()), status);
    }

    private String candidateEnvelope(AliasQuery query, List<ImportTarget> targets) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.set("query", objectMapper.valueToTree(query));
        ArrayNode candidates = root.putArray("candidates");
        for (ImportTarget target : targets) {
            ObjectNode candidate = candidates.addObject();
            String targetJson = writeJson(target);
            candidate.put("candidateId", sha256(targetJson));
            try {
                candidate.set("target", objectMapper.readTree(targetJson));
            } catch (JacksonException exception) {
                throw new IllegalStateException("TMDB candidate could not be serialized", exception);
            }
        }
        return writeJson(root);
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

    private boolean isWaiting(String outcome) {
        return "AMBIGUOUS".equals(outcome) || "NOT_FOUND".equals(outcome);
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

    private AliasQuery queryFor(ImportItem item) {
        String payload = item.getPayloadJson();
        if (payload == null || payload.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode source = root.path("source");
            JsonNode query = root.path("sourceQuery");
            if (!query.isObject()) query = root.path("query");
            if (!query.isObject()) query = source.path("sourceQuery");
            if (!query.isObject()) query = source.path("query");
            if (!query.isObject()) return null;
            return objectMapper.treeToValue(query, AliasQuery.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Import item search query is invalid", exception);
        }
    }

    private QueryKey key(AliasQuery query) {
        return new QueryKey(query.kind(), identityNormalizer.normalizeTitle(query.title()),
                query.year(), query.contentType());
    }

    private String normalizedContentKey(AliasQuery query) {
        String normalizedTitle = identityNormalizer.normalizeTitle(query.title());
        return query.kind().name() + "|" + normalizedTitle.length() + ":" + normalizedTitle
                + "|" + (query.year() == null ? "" : query.year())
                + "|" + (query.contentType() == null ? "" : query.contentType().name());
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Import value could not be serialized", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record ClaimedJob(UUID id, UUID ownerId, long previewVersion, String selectionJson,
                              String sourceNamespace, ImportInputKind inputKind,
                              com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat sourceFormat,
                              String storageKey, String timezone, ImportJobState state,
                              String workPhase, String checkpoint, UUID leaseToken) { }

    private record QueryKey(AliasQuery.Kind kind, String normalizedTitle, Integer year, ContentType type) { }

    private record StageResult(String outcome, String resolvedTargetJson, String issue) { }

    private static final class StaleImportExecutionException extends RuntimeException { }
}
