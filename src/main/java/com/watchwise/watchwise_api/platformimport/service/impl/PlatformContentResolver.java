package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.AliasQuery;
import com.watchwise.watchwise_api.platformimport.model.AliasResolution;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.ExternalContentAliasService;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportIdentityNormalizer;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PlatformContentResolver {
    private static final int MAX_BATCH_SIZE = 200;
    private static final List<String> REUSABLE_OUTCOMES = List.of("MATCHED", "AMBIGUOUS", "NOT_FOUND");

    private final ImportItemRepository itemRepository;
    private final ImportJobRepository jobRepository;
    private final ImportUnitTransaction unitTransaction;
    private final ExternalContentAliasService aliasService;
    private final ImportIdentityNormalizer identityNormalizer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PlatformContentResolver(ImportItemRepository itemRepository, ImportJobRepository jobRepository,
                                   ImportUnitTransaction unitTransaction, ExternalContentAliasService aliasService,
                                   ImportIdentityNormalizer identityNormalizer, ObjectMapper objectMapper, Clock clock) {
        this.itemRepository = itemRepository;
        this.jobRepository = jobRepository;
        this.unitTransaction = unitTransaction;
        this.aliasService = aliasService;
        this.identityNormalizer = identityNormalizer;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<ResolutionResult> resolve(ImportExecutionContext context, List<ImportItem> batch) {
        if (context == null || batch == null || batch.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Content resolution requires a job context and at most 200 items");
        }
        if (batch.isEmpty()) return List.of();
        batch = validateContext(context, batch);

        Map<QueryKey, List<ImportItem>> groups = new LinkedHashMap<>();
        Map<QueryKey, String> contentKeys = new LinkedHashMap<>();
        Map<UUID, ResolutionResult> results = new LinkedHashMap<>();
        List<ImportUnitTransaction.ResolutionUpdate> updates = new ArrayList<>(batch.size());

        for (ImportItem item : batch) {
            if (item.getDecision() == ImportDecision.SKIP_ITEM) {
                addUpdate(updates, results, item, new StageResult("RESOLVED", item.getResolvedTargetJson(),
                        item.getNormalizedContentKey(), item.getResolvedContentKey(), null, null));
                continue;
            }
            AliasQuery query = queryFor(item);
            if (query == null) {
                if (item.getResolvedTargetJson() == null) {
                    throw new IllegalStateException("Import item has neither a resolved target nor an alias query");
                }
                if (isCandidateEnvelope(item.getResolvedTargetJson())) {
                    throw new IllegalStateException("Unresolved import item has no alias query");
                }
                ImportTarget target = targetFor(item.getResolvedTargetJson());
                String contentKey = contentKey(target);
                addUpdate(updates, results, item, new StageResult("MATCHED", item.getResolvedTargetJson(),
                        item.getNormalizedContentKey(), contentKey, target, null));
                continue;
            }

            QueryKey key = key(query);
            String normalizedKey = normalizedContentKey(query);
            if (item.getResolvedTargetJson() != null && !isCandidateEnvelope(item.getResolvedTargetJson())) {
                ImportTarget target = targetFor(item.getResolvedTargetJson());
                addUpdate(updates, results, item, new StageResult("MATCHED", item.getResolvedTargetJson(),
                        normalizedKey, contentKey(target), target, null));
            } else {
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
                contentKeys.putIfAbsent(key, normalizedKey);
            }
        }

        Map<String, ImportItem> stagedByContentKey = new LinkedHashMap<>();
        if (!contentKeys.isEmpty()) {
            for (ImportItem staged : itemRepository.findStagedResolutions(context.jobId(), context.ownerId(),
                    context.planGeneration(), contentKeys.values(), REUSABLE_OUTCOMES)) {
                stagedByContentKey.putIfAbsent(staged.getNormalizedContentKey(), staged);
            }
        }

        List<Map.Entry<QueryKey, List<ImportItem>>> remote = new ArrayList<>();
        for (Map.Entry<QueryKey, List<ImportItem>> entry : groups.entrySet()) {
            String normalizedKey = contentKeys.get(entry.getKey());
            ImportItem stagedItem = stagedByContentKey.get(normalizedKey);
            if (stagedItem == null) {
                remote.add(entry);
                continue;
            }
            StageResult cached = new StageResult(stagedItem.getOutcome(), stagedItem.getResolvedTargetJson(),
                    stagedItem.getNormalizedContentKey(), stagedItem.getResolvedContentKey(),
                    targetForIfResolved(stagedItem), stagedItem.getIssue());
            for (ImportItem item : entry.getValue()) addUpdate(updates, results, item, cached);
        }

        List<AliasQuery> queries = remote.stream().map(entry -> queryFor(entry.getValue().getFirst())).toList();
        if (queries.size() > MAX_BATCH_SIZE) {
            throw new IllegalStateException("Content resolver exceeded its distinct-query batch limit");
        }
        if (!queries.isEmpty()) {
            List<AliasResolution> resolutions = aliasService.resolveBatch(context.ownerId(), queries);
            if (resolutions.size() != remote.size()) {
                throw new IllegalStateException("TMDB resolver returned an invalid batch");
            }
            for (int index = 0; index < remote.size(); index++) {
                Map.Entry<QueryKey, List<ImportItem>> entry = remote.get(index);
                StageResult resolved = stage(queries.get(index), resolutions.get(index));
                for (ImportItem item : entry.getValue()) addUpdate(updates, results, item, resolved);
            }
        }

        if (!updates.isEmpty() && !unitTransaction.saveResolution(context, List.copyOf(updates))) {
            throw new StaleImportExecutionException();
        }
        return batch.stream().map(item -> results.get(item.getId())).toList();
    }

    private List<ImportItem> validateContext(ImportExecutionContext context, List<ImportItem> batch) {
        ImportJob job = jobRepository.findOwnedById(context.jobId(), context.ownerId())
                .orElseThrow(() -> new IllegalStateException("Import job does not belong to the resolver owner"));
        if (job.getPreviewVersion() != context.previewVersion()
                || !"RESOLVE".equals(job.getWorkPhase())
                || job.getState() != com.watchwise.watchwise_api.platformimport.model.ImportJobState.RESOLVING
                || !job.isWorkPending()
                || job.getLeaseToken() == null || !job.getLeaseToken().equals(context.leaseToken())
                || job.getLeaseUntil() == null || !job.getLeaseUntil().isAfter(LocalDateTime.now(clock))
                || !context.sourceNamespace().equals(job.getSourceNamespace())
                || readPlanGeneration(job.getSelectionJson()) != context.planGeneration()) {
            throw new StaleImportExecutionException();
        }
        if (batch.stream().anyMatch(item -> !context.jobId().equals(item.getJobId())
                || context.planGeneration() != item.getPlanGeneration()
                || !context.sourceNamespace().equals(item.getSourceNamespace()))) {
            throw new IllegalArgumentException("Content resolver batch contains an item outside its job generation");
        }
        if (batch.stream().map(ImportItem::getId).distinct().count() != batch.size()) {
            throw new IllegalArgumentException("Content resolver batch contains duplicate items");
        }
        List<ImportItem> storedItems = itemRepository.findAllByJobIdAndPlanGenerationAndIdIn(
                context.jobId(), context.planGeneration(), batch.stream().map(ImportItem::getId).toList());
        if (storedItems.size() != batch.size()) {
            throw new IllegalArgumentException("Content resolver batch contains an item outside its job staging");
        }
        Map<UUID, ImportItem> storedById = new LinkedHashMap<>();
        for (ImportItem item : storedItems) storedById.put(item.getId(), item);
        List<ImportItem> canonicalBatch = batch.stream().map(item -> storedById.get(item.getId())).toList();
        if (canonicalBatch.stream().anyMatch(item -> !context.sourceNamespace().equals(item.getSourceNamespace()))) {
            throw new IllegalArgumentException("Content resolver staging namespace does not match its job");
        }
        return canonicalBatch;
    }

    private long readPlanGeneration(String selectionJson) {
        try {
            JsonNode root = objectMapper.readTree(selectionJson);
            JsonNode version = root.get("schemaVersion");
            JsonNode generation = root.get("planGeneration");
            if (version == null && generation == null) return 0;
            if (version == null || !version.isInt() || version.asInt() != 1
                    || generation == null || !generation.canConvertToLong() || generation.asLong() < 0) {
                throw new IllegalStateException("Stored import plan generation is invalid");
            }
            return generation.asLong();
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored import plan is invalid", exception);
        }
    }

    private void addUpdate(List<ImportUnitTransaction.ResolutionUpdate> updates,
                           Map<UUID, ResolutionResult> results, ImportItem item, StageResult staged) {
        updates.add(new ImportUnitTransaction.ResolutionUpdate(item.getId(), staged.outcome(),
                staged.resolvedTargetJson(), staged.normalizedContentKey(), staged.resolvedContentKey(),
                staged.issue()));
        results.put(item.getId(), new ResolutionResult(item.getId(), staged.outcome(), staged.target(), staged.issue()));
    }

    private StageResult stage(AliasQuery query, AliasResolution resolution) {
        if (resolution.status() == AliasResolution.Status.MATCHED) {
            ImportTarget target = resolution.candidates().getFirst();
            return new StageResult("MATCHED", writeJson(target), normalizedContentKey(query),
                    contentKey(target), target, null);
        }
        String outcome = resolution.status().name();
        return new StageResult(outcome, candidateEnvelope(query, resolution.candidates()), normalizedContentKey(query),
                null, null, outcome);
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
        String title = identityNormalizer.normalizeTitle(query.title());
        return query.kind().name() + "|" + title.length() + ":" + title
                + "|" + (query.year() == null ? "" : query.year())
                + "|" + (query.contentType() == null ? "" : query.contentType().name());
    }

    private boolean isCandidateEnvelope(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return root.isObject() && root.path("schemaVersion").asInt(-1) == 1
                    && root.path("query").isObject() && root.path("candidates").isArray();
        } catch (JacksonException exception) {
            return false;
        }
    }

    private ImportTarget targetForIfResolved(ImportItem item) {
        return item.getResolvedTargetJson() == null || isCandidateEnvelope(item.getResolvedTargetJson())
                ? null : targetFor(item.getResolvedTargetJson());
    }

    private ImportTarget targetFor(String json) {
        try {
            return objectMapper.treeToValue(objectMapper.readTree(json), ImportTarget.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Resolved import target is invalid", exception);
        }
    }

    private String contentKey(ImportTarget target) {
        if (target instanceof ImportTarget.PersonTarget person) return "PERSON|" + person.personTmdbId();
        MediaReference content = ((ImportTarget.ContentTarget) target).content();
        return switch (content.type()) {
            case MOVIE, SERIES -> "CONTENT|" + content.type().name() + "|" + content.tmdbId();
            case SEASON -> "CONTENT|SEASON|" + content.seriesTmdbId() + "|" + content.seasonNumber();
            case EPISODE -> "CONTENT|EPISODE|" + content.seriesTmdbId() + "|"
                    + content.seasonNumber() + "|" + content.episodeNumber();
        };
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

    public record ResolutionResult(UUID itemId, String outcome, ImportTarget target, String issue) {
        public boolean unavailable() {
            return "UNAVAILABLE".equals(outcome);
        }
    }

    private record QueryKey(AliasQuery.Kind kind, String normalizedTitle, Integer year, ContentType contentType) { }

    private record StageResult(String outcome, String resolvedTargetJson, String normalizedContentKey,
                               String resolvedContentKey, ImportTarget target, String issue) { }

    private static final class StaleImportExecutionException extends RuntimeException { }
}
