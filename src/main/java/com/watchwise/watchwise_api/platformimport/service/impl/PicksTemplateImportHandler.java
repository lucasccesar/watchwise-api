package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateCreation;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateCreationService;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateDuplicateTargetException;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateTargetNotFoundException;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateTargetValidationException;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.*;
import com.watchwise.watchwise_api.platformimport.parser.PicksTemplateJsonParser;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.platformimport.service.ImportModeHandler;
import com.watchwise.watchwise_api.platformimport.storage.ImportFileStore;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class PicksTemplateImportHandler implements ImportModeHandler {
    static final String ROOT_SOURCE_ID = "template-root";
    static final String DUPLICATE_NOMINEE_ISSUE = "DUPLICATE_TEMPLATE_NOMINEE";
    private static final int OVERLAY_SCHEMA_VERSION = 1;
    private static final String OVERLAY_MARKER = "_watchwiseResolution";
    private static final String PREPARED_PLAN_KEY = "preparedTemplate";

    private final ImportFileStore files;
    private final PicksTemplateJsonParser parser;
    private final TemplateNomineeResolver nomineeResolver;
    private final ImportedTemplateAdapter adapter;
    private final PreparedTemplateCreationService preparedCreations;
    private final ImportSemanticFingerprint fingerprints;
    private final ImportItemRepository items;
    private final ImportUnitTransaction unitTransactions;
    private final ImportLedger ledger;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PicksTemplateImportHandler(ImportFileStore files, PicksTemplateJsonParser parser,
                                      TemplateNomineeResolver nomineeResolver,
                                      ImportedTemplateAdapter adapter,
                                      PreparedTemplateCreationService preparedCreations,
                                      ImportSemanticFingerprint fingerprints,
                                      ImportItemRepository items, ImportUnitTransaction unitTransactions,
                                      ImportLedger ledger,
                                      ObjectMapper mapper, Clock clock) {
        this.files = files;
        this.parser = parser;
        this.nomineeResolver = nomineeResolver;
        this.adapter = adapter;
        this.preparedCreations = preparedCreations;
        this.fingerprints = fingerprints;
        this.items = items;
        this.unitTransactions = unitTransactions;
        this.ledger = ledger;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public ImportModeKey mode() {
        return new ImportModeKey(ImportInputKind.PICKS_TEMPLATE, ImportSourceFormat.WATCHWISE_TEMPLATE_JSON);
    }

    @Override
    public void validateUpload(InputStream input) {
        parser.parse(input);
    }

    @Override
    public ImportInventory inspect(ImportReadContext context) {
        return new ImportInventory(List.of(), List.of());
    }

    @Override
    public void normalize(ImportReadContext context, ImportSelection selection,
                          Consumer<NormalizedImportRecord> sink) {
        TemplateImportDocument document;
        try (InputStream input = files.open(context.storageKey())) {
            document = parser.parse(input);
        } catch (IOException exception) {
            throw new BadRequestException("Picks template file could not be read");
        }

        int ordinal = 0;
        ObjectNode templatePayload = mapper.createObjectNode();
        templatePayload.put("schemaVersion", document.schemaVersion());
        templatePayload.put("title", document.title());
        putNullable(templatePayload, "description", document.description());
        putNullable(templatePayload, "coverImage", document.coverImage());
        putNullable(templatePayload, "instructions", document.instructions());
        putDate(templatePayload, "eligibilityStart", document.eligibilityStart());
        putDate(templatePayload, "eligibilityEnd", document.eligibilityEnd());
        sink.accept(record(context, ROOT_SOURCE_ID, ImportRecordKind.TEMPLATE,
                "template", ROOT_SOURCE_ID, null, null, templatePayload, ordinal++));

        for (int categoryIndex = 0; categoryIndex < document.categories().size(); categoryIndex++) {
            ImportedTemplateCategory category = document.categories().get(categoryIndex);
            String categoryId = categorySourceId(categoryIndex);
            ObjectNode categoryPayload = mapper.createObjectNode();
            categoryPayload.put("name", category.name());
            putNullable(categoryPayload, "description", category.description());
            categoryPayload.put("type", category.type().name());
            categoryPayload.put("group", category.group().name());
            categoryPayload.put("displayOrder", category.displayOrder());
            categoryPayload.put("optionMode", category.nomineesPresent() ? "FIXED" : "OPEN");
            categoryPayload.put("nomineesPresent", category.nomineesPresent());
            sink.accept(record(context, categoryId, ImportRecordKind.TEMPLATE_CATEGORY,
                    "template_category", categoryId, ROOT_SOURCE_ID, null, categoryPayload, ordinal++));

            for (int nomineeIndex = 0; nomineeIndex < category.nominees().size(); nomineeIndex++) {
                JsonNode nominee = category.nominees().get(nomineeIndex);
                String optionId = optionSourceId(categoryIndex, nomineeIndex);
                ImportTarget target = strongTarget(context.ownerId(), category.type(), nominee);
                AliasQuery query = hasAliasQuery(category.type(), nominee) ? query(category.type(), nominee) : null;
                if (target == null && query == null) {
                    throw new IllegalStateException("Parsed template nominee has neither a strong target nor a query");
                }
                ObjectNode optionPayload = mapper.createObjectNode();
                optionPayload.set("nominee", nominee.deepCopy());
                optionPayload.put("targetType", category.type().name());
                if (query != null) optionPayload.set("sourceQuery", mapper.valueToTree(query));
                sink.accept(record(context, optionId, ImportRecordKind.TEMPLATE_OPTION,
                        "template_option", optionId, categoryId, target, optionPayload, ordinal++));
            }
        }
    }

    @Override
    public void validateResolved(ImportExecutionContext context) {
        List<ImportItem> categories = items.findAllByJobIdAndPlanGenerationAndNormalizedKind(
                context.jobId(), context.planGeneration(), ImportRecordKind.TEMPLATE_CATEGORY);
        List<ImportItem> options = items.findAllByJobIdAndPlanGenerationAndNormalizedKind(
                context.jobId(), context.planGeneration(), ImportRecordKind.TEMPLATE_OPTION);
        Map<String, List<ImportItem>> optionsByCategory = new HashMap<>();
        for (ImportItem option : options) {
            optionsByCategory.computeIfAbsent(option.getParentSourceId(), ignored -> new ArrayList<>()).add(option);
        }
        Set<UUID> duplicateIds = new HashSet<>();
        Map<String, ImportItem> categoriesById = new HashMap<>();
        for (ImportItem category : categories) {
            categoriesById.put(category.getSourceId(), category);
            if (!isActiveFixedCategory(category)) continue;
            List<ImportItem> nominees = optionsByCategory.getOrDefault(category.getSourceId(), List.of()).stream()
                    .sorted(Comparator.comparingLong(ImportItem::getOrdinal)).toList();
            Set<ImportTarget> seen = new HashSet<>();
            for (ImportItem nominee : nominees) {
                if (isRemoved(nominee)) continue;
                ImportTarget target = resolvedTarget(nominee);
                if (target != null) {
                    JsonNode sourceNominee = sourcePayload(readPayload(nominee.getPayloadJson())).path("nominee");
                    target = withSourcePersonContext(target, sourceNominee);
                    if (!seen.add(target)) duplicateIds.add(nominee.getId());
                }
            }
        }

        List<ImportUnitTransaction.TemplateResolutionIssueUpdate> updates = new ArrayList<>();
        for (ImportItem option : options) {
            boolean duplicate = duplicateIds.contains(option.getId());
            boolean oldDuplicate = isDuplicateIssue(option.getIssue());
            ImportItem category = categoriesById.get(option.getParentSourceId());
            boolean inactiveCategory = category == null || !isActiveFixedCategory(category);
            if (isRemoved(option) && option.getIssue() != null) {
                updates.add(new ImportUnitTransaction.TemplateResolutionIssueUpdate(
                        option.getId(), "RESOLVED", null));
            } else if (inactiveCategory && (!"RESOLVED".equals(option.getOutcome()) || option.getIssue() != null)) {
                updates.add(new ImportUnitTransaction.TemplateResolutionIssueUpdate(
                        option.getId(), "RESOLVED", null));
            } else if (duplicate && !oldDuplicate) {
                updates.add(new ImportUnitTransaction.TemplateResolutionIssueUpdate(option.getId(),
                        "NORMALIZATION_ISSUE", DUPLICATE_NOMINEE_ISSUE
                        + ": Remove one of the repeated nominees or resolve it to a different target."));
            } else if (!duplicate && oldDuplicate) {
                String outcome = isRemoved(option) ? "RESOLVED" : option.getOutcome();
                if (outcome == null || "NORMALIZATION_ISSUE".equals(outcome)) outcome = "MATCHED";
                updates.add(new ImportUnitTransaction.TemplateResolutionIssueUpdate(option.getId(), outcome, null));
            }
        }
        if (!unitTransactions.updateTemplateResolutionIssues(context, updates)) {
            throw new IllegalStateException("Template resolution plan changed while validating nominees");
        }
        if (!duplicateIds.isEmpty() || hasUnresolvedActiveNominees(categories, optionsByCategory)) return;

        ImportItem root = items.findFirstByJobIdAndPlanGenerationAndNormalizedKindAndSourceId(
                        context.jobId(), context.planGeneration(), ImportRecordKind.TEMPLATE, ROOT_SOURCE_ID)
                .orElseThrow(() -> new IllegalStateException("Picks template root is missing from import staging"));
        if (isEligibilityPeriodIssue(root.getIssue())) return;
        EffectiveTemplate effective = effectiveTemplate(context, root);
        String fingerprint = fingerprints.ofTemplate(effective.document(), effective.resolvedOptions());
        ImportSourceKey sourceKey = new ImportSourceKey("WATCHWISE_TEMPLATE_V1", "PICKS_TEMPLATE", fingerprint);
        JsonNode payload = readPayload(root.getPayloadJson());
        JsonNode cached = resolution(payload).path(PREPARED_PLAN_KEY);
        PreparedTemplateCreation cachedCreation = cachedCreation(cached, context.previewVersion());
        if (cachedCreation != null && fingerprint.equals(text(cached, "semanticFingerprint"))) return;
        if (ledger.isConsumed(context.ownerId(), sourceKey)) {
            persistPreparedPlan(context, root, payload, fingerprint, null);
            return;
        }
        try {
            PreparedTemplateCreation prepared = preparedCreations.prepare(context.ownerId(), effective.creation());
            persistPreparedPlan(context, root, payload, fingerprint, prepared);
        } catch (PreparedTemplateTargetValidationException exception) {
            updatePreparedTargetIssue(context, effective, exception.position().categoryIndex(), exception.position().optionIndex(),
                    "FIXED_OPTION_INVALID: Resolve the nominee to a valid target or remove it.");
        } catch (PreparedTemplateTargetNotFoundException exception) {
            updatePreparedTargetIssue(context, effective, exception.position().categoryIndex(), exception.position().optionIndex(),
                    "FIXED_OPTION_NOT_FOUND: Resolve the nominee to an existing target or remove it.");
        } catch (PreparedTemplateDuplicateTargetException exception) {
            updatePreparedTargetIssue(context, effective, exception.position().categoryIndex(), exception.position().optionIndex(),
                    DUPLICATE_NOMINEE_ISSUE
                            + ": Remove one of the repeated nominees or resolve it to a different target.");
        }
    }

    private boolean hasUnresolvedActiveNominees(List<ImportItem> categories,
                                                Map<String, List<ImportItem>> optionsByCategory) {
        for (ImportItem category : categories) {
            if (!isActiveFixedCategory(category)) continue;
            for (ImportItem option : optionsByCategory.getOrDefault(category.getSourceId(), List.of())) {
                if (isRemoved(option)) continue;
                if (isDuplicateIssue(option.getIssue())) continue;
                if (option.getIssue() != null || resolvedTarget(option) == null) return true;
            }
        }
        return false;
    }

    private void updatePreparedTargetIssue(ImportExecutionContext context, EffectiveTemplate effective,
                                           int categoryIndex, int optionIndex, String issue) {
        if (categoryIndex < 0 || categoryIndex >= effective.optionItemIds().size()) {
            throw new IllegalStateException("Prepared target validation returned an invalid category index");
        }
        List<UUID> optionIds = effective.optionItemIds().get(categoryIndex);
        if (optionIndex < 0 || optionIndex >= optionIds.size()) {
            throw new IllegalStateException("Prepared target validation returned an invalid nominee index");
        }
        boolean updated = unitTransactions.updateTemplateResolutionIssues(context, List.of(
                new ImportUnitTransaction.TemplateResolutionIssueUpdate(optionIds.get(optionIndex),
                        "NORMALIZATION_ISSUE", issue)));
        if (!updated) throw new IllegalStateException("Template validation issue could not be staged");
    }

    private void returnToResolutionForTargetIssue(ImportExecutionContext context, ImportItem root,
                                                  EffectiveTemplate effective, int categoryIndex,
                                                  int optionIndex, String issue) {
        if (categoryIndex < 0 || categoryIndex >= effective.optionItemIds().size()) {
            throw new IllegalStateException("Prepared target validation returned an invalid category index");
        }
        List<UUID> optionIds = effective.optionItemIds().get(categoryIndex);
        if (optionIndex < 0 || optionIndex >= optionIds.size()) {
            throw new IllegalStateException("Prepared target validation returned an invalid nominee index");
        }
        if (!unitTransactions.returnTemplateToResolution(context, root.getId(), optionIds.get(optionIndex), issue)) {
            throw new IllegalStateException("Picks template target could not be returned to resolution");
        }
    }

    @Override
    public void prepareForApply(ImportExecutionContext context) {
        ImportItem root = items.findFirstByJobIdAndPlanGenerationAndNormalizedKindAndSourceId(
                        context.jobId(), context.planGeneration(), ImportRecordKind.TEMPLATE, ROOT_SOURCE_ID)
                .orElseThrow(() -> new IllegalStateException("Picks template root is missing from import staging"));
        if ("APPLIED".equals(root.getOutcome()) || "SKIPPED".equals(root.getOutcome())) return;

        JsonNode payload = readPayload(root.getPayloadJson());
        JsonNode cached = resolution(payload).path(PREPARED_PLAN_KEY);
        PreparedTemplateCreation cachedCreation = cachedCreation(cached, context.previewVersion());
        EffectiveTemplate effective = effectiveTemplate(context, root);
        String fingerprint = fingerprints.ofTemplate(effective.document(), effective.resolvedOptions());
        if (cachedCreation != null && fingerprint.equals(text(cached, "semanticFingerprint"))) return;

        ImportSourceKey sourceKey = new ImportSourceKey("WATCHWISE_TEMPLATE_V1", "PICKS_TEMPLATE", fingerprint);
        if (ledger.isConsumed(context.ownerId(), sourceKey)) {
            persistPreparedPlan(context, root, payload, fingerprint, null);
            return;
        }

        try {
            PreparedTemplateCreation prepared = preparedCreations.prepare(context.ownerId(), effective.creation());
            persistPreparedPlan(context, root, payload, fingerprint, prepared);
        } catch (PreparedTemplateTargetValidationException exception) {
            returnToResolutionForTargetIssue(context, root, effective,
                    exception.position().categoryIndex(), exception.position().optionIndex(),
                    "FIXED_OPTION_INVALID: Resolve the nominee to a valid target or remove it.");
        } catch (PreparedTemplateTargetNotFoundException exception) {
            returnToResolutionForTargetIssue(context, root, effective,
                    exception.position().categoryIndex(), exception.position().optionIndex(),
                    "FIXED_OPTION_NOT_FOUND: Resolve the nominee to an existing target or remove it.");
        } catch (PreparedTemplateDuplicateTargetException exception) {
            returnToResolutionForTargetIssue(context, root, effective,
                    exception.position().categoryIndex(), exception.position().optionIndex(), DUPLICATE_NOMINEE_ISSUE
                            + ": Remove one of the repeated nominees or resolve it to a different target.");
        }
    }

    private void persistPreparedPlan(ImportExecutionContext context, ImportItem root, JsonNode payload,
                                     String fingerprint, PreparedTemplateCreation prepared) {
        ObjectNode overlay = decisionOverlay(payload);
        ObjectNode resolution = (ObjectNode) resolution(overlay);
        ObjectNode plan = resolution.putObject(PREPARED_PLAN_KEY);
        plan.put("schemaVersion", 1);
        plan.put("previewVersion", context.previewVersion());
        plan.put("semanticFingerprint", fingerprint);
        if (prepared != null) plan.set("preparedCreation", mapper.valueToTree(prepared));
        if (!unitTransactions.savePreparedTemplatePlan(context, root.getId(), writeJson(overlay))) {
            throw new IllegalStateException("Picks template plan changed before it could be staged");
        }
    }

    private EffectiveTemplate effectiveTemplate(ImportExecutionContext context, ImportItem root) {
        JsonNode rootPayload = readPayload(root.getPayloadJson());
        JsonNode source = sourcePayload(rootPayload);
        JsonNode fields = resolution(rootPayload).path("fields");
        String title = field(fields, "TITLE", source, "title");
        String description = field(fields, "DESCRIPTION", source, "description");
        String coverImage = field(fields, "COVER_IMAGE", source, "coverImage");
        String instructions = text(source, "instructions");
        LocalDate start = date(field(fields, "ELIGIBILITY_START", source, "eligibilityStart"));
        LocalDate end = date(field(fields, "ELIGIBILITY_END", source, "eligibilityEnd"));

        List<ImportItem> sourceCategories = items.findAllByJobIdAndPlanGenerationAndNormalizedKind(
                        context.jobId(), context.planGeneration(), ImportRecordKind.TEMPLATE_CATEGORY).stream()
                .sorted(Comparator.comparingLong(ImportItem::getOrdinal)).toList();
        List<ImportItem> sourceOptions = items.findAllByJobIdAndPlanGenerationAndNormalizedKind(
                        context.jobId(), context.planGeneration(), ImportRecordKind.TEMPLATE_OPTION);
        Map<String, List<ImportItem>> optionsByCategory = new HashMap<>();
        for (ImportItem option : sourceOptions) {
            optionsByCategory.computeIfAbsent(option.getParentSourceId(), ignored -> new ArrayList<>()).add(option);
        }

        List<ImportedTemplateCategory> categories = new ArrayList<>();
        List<List<ImportTarget>> resolvedOptions = new ArrayList<>();
        List<List<UUID>> optionItemIds = new ArrayList<>();
        for (ImportItem categoryItem : sourceCategories) {
            if (isRemoved(categoryItem)) continue;
            JsonNode categoryPayload = readPayload(categoryItem.getPayloadJson());
            JsonNode categorySource = sourcePayload(categoryPayload);
            JsonNode categoryFields = resolution(categoryPayload).path("fields");
            PickAllowedType type = PickAllowedType.valueOf(requiredText(categorySource, "type"));
            PickCategoryGroup group = PickCategoryGroup.valueOf(requiredText(categorySource, "group"));
            String name = field(categoryFields, "CATEGORY_NAME", categorySource, "name");
            String categoryDescription = text(categorySource, "description");
            int displayOrder = categorySource.path("displayOrder").asInt();
            boolean fixed = "FIXED".equalsIgnoreCase(text(categorySource, "optionMode"));
            JsonNode setOpen = resolution(categoryPayload).path("setCategoryOpen");
            boolean setOpenValue = setOpen.path("open").asBoolean(false);
            if (categoryItem.getDecision() == ImportDecision.SET_CATEGORY_OPEN && setOpenValue) fixed = false;

            List<JsonNode> nominees = new ArrayList<>();
            List<ImportTarget> targets = new ArrayList<>();
            List<UUID> optionIds = new ArrayList<>();
            if (fixed) {
                List<ImportItem> categoryOptions = optionsByCategory.getOrDefault(categoryItem.getSourceId(), List.of())
                        .stream().sorted(Comparator.comparingLong(ImportItem::getOrdinal)).toList();
                for (ImportItem option : categoryOptions) {
                    if (isRemoved(option)) continue;
                    JsonNode optionSource = sourcePayload(readPayload(option.getPayloadJson()));
                    JsonNode nominee = optionSource.get("nominee");
                    if (nominee == null) throw new BadRequestException("Template nominee source is missing");
                    ImportTarget target = resolvedTarget(option);
                    if (target == null) throw new ConflictException("Every fixed nominee must be resolved before import");
                    targets.add(withSourcePersonContext(target, nominee));
                    nominees.add(nominee.deepCopy());
                    optionIds.add(option.getId());
                }
                if (targets.isEmpty()) {
                    throw new BadRequestException("A fixed template category must contain at least one nominee");
                }
            }
            categories.add(new ImportedTemplateCategory(name, categoryDescription, type, group,
                    displayOrder, List.copyOf(nominees), fixed));
            resolvedOptions.add(List.copyOf(targets));
            optionItemIds.add(List.copyOf(optionIds));
        }
        if (categories.isEmpty()) throw new BadRequestException("A Picks template must retain at least one category");
        TemplateImportDocument document = new TemplateImportDocument(1, title, description, coverImage,
                instructions, start, end, List.copyOf(categories));
        PicksTemplateCreationDTO creation = adapter.toCreation(document, resolvedOptions);
        return new EffectiveTemplate(document, List.copyOf(resolvedOptions), List.copyOf(optionItemIds), creation);
    }

    private PreparedTemplateCreation cachedCreation(JsonNode cached, long previewVersion) {
        if (cached == null || !cached.isObject() || cached.path("schemaVersion").asInt(-1) != 1
                || !cached.path("previewVersion").canConvertToLong()
                || cached.path("previewVersion").asLong() != previewVersion
                || !cached.path("semanticFingerprint").isTextual()
                || !cached.path("preparedCreation").isObject()) return null;
        try {
            PreparedTemplateCreation prepared = mapper.treeToValue(cached.path("preparedCreation"),
                    PreparedTemplateCreation.class);
            return prepared.isFreshAt(java.time.LocalDateTime.now(clock)) ? prepared : null;
        } catch (JacksonException | IllegalArgumentException exception) {
            return null;
        }
    }

    private ImportTarget strongTarget(UUID owner, PickAllowedType type, JsonNode nominee) {
        if (!nominee.isObject()) return null;
        boolean hasStrongIdentity = type == PickAllowedType.EPISODE
                || type == PickAllowedType.PERSON && nominee.has("personTmdbId")
                || type != PickAllowedType.PERSON && nominee.has("tmdbId");
        if (!hasStrongIdentity) return null;
        AliasResolution resolution = nomineeResolver.resolve(owner, type, nominee);
        if (resolution.status() != AliasResolution.Status.MATCHED) {
            throw new BadRequestException("Typed template nominee could not be resolved");
        }
        return resolution.candidates().getFirst();
    }

    private AliasQuery query(PickAllowedType type, JsonNode nominee) {
        if (type == PickAllowedType.PERSON) {
            String name = nominee.isTextual() ? nominee.asString() : requiredText(nominee, "name");
            return AliasQuery.person(name);
        }
        String title = nominee.isTextual() ? nominee.asString() : requiredText(nominee, "title");
        Integer year = !nominee.isTextual() && nominee.has("releaseYear")
                ? nominee.path("releaseYear").asInt() : null;
        return new AliasQuery(title, year, type.toContentType());
    }

    private boolean hasAliasQuery(PickAllowedType type, JsonNode nominee) {
        if (nominee.isTextual()) return true;
        return type == PickAllowedType.PERSON
                ? nominee.path("name").isTextual()
                : type != PickAllowedType.EPISODE && nominee.path("title").isTextual();
    }

    private NormalizedImportRecord record(ImportReadContext context, String itemId, ImportRecordKind kind,
                                          String objectKind, String sourceId, String parentSourceId,
                                          ImportTarget target, JsonNode payload, int ordinal) {
        return new NormalizedImportRecord(itemId, kind,
                new ImportSourceKey(context.sourceNamespace(), objectKind, sourceId), parentSourceId,
                target, payload, ordinal);
    }

    private ImportTarget resolvedTarget(ImportItem item) {
        if (item.getResolvedTargetJson() == null || isCandidateEnvelope(item.getResolvedTargetJson())) return null;
        try {
            return mapper.treeToValue(mapper.readTree(item.getResolvedTargetJson()), ImportTarget.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Resolved Picks nominee is invalid", exception);
        }
    }

    private ImportTarget withSourcePersonContext(ImportTarget target, JsonNode nominee) {
        if (!(target instanceof ImportTarget.PersonTarget person)) return target;
        MediaReference context = nomineeResolver.explicitPersonContext(nominee);
        return context == null ? target : new ImportTarget.PersonTarget(person.personTmdbId(), context);
    }

    private boolean isActiveFixedCategory(ImportItem item) {
        if (isRemoved(item)) return false;
        JsonNode source = sourcePayload(readPayload(item.getPayloadJson()));
        boolean fixed = "FIXED".equalsIgnoreCase(text(source, "optionMode"));
        JsonNode open = resolution(readPayload(item.getPayloadJson())).path("setCategoryOpen");
        return fixed && !(item.getDecision() == ImportDecision.SET_CATEGORY_OPEN
                && open.path("open").asBoolean(false));
    }

    private boolean isRemoved(ImportItem item) {
        return item.getDecision() == ImportDecision.REMOVE_CATEGORY
                || item.getDecision() == ImportDecision.REMOVE_NOMINEE
                || item.getDecision() == ImportDecision.SKIP_ITEM;
    }

    private boolean isDuplicateIssue(String issue) {
        return issue != null && issue.startsWith(DUPLICATE_NOMINEE_ISSUE);
    }

    private boolean isEligibilityPeriodIssue(String issue) {
        return issue != null && issue.startsWith("TEMPLATE_ELIGIBILITY_PERIOD_INVALID");
    }

    private boolean isCandidateEnvelope(String json) {
        try {
            JsonNode value = mapper.readTree(json);
            return value.isObject() && value.path("schemaVersion").asInt(-1) == 1
                    && value.path("candidates").isArray() && value.path("query").isObject();
        } catch (JacksonException exception) {
            throw new IllegalStateException("Resolved candidate envelope is invalid", exception);
        }
    }

    private ObjectNode decisionOverlay(JsonNode payload) {
        if (payload.isObject() && payload.path(OVERLAY_MARKER).asInt(-1) == OVERLAY_SCHEMA_VERSION
                && payload.path("source").isObject() && payload.path("resolution").isObject()) {
            return (ObjectNode) payload.deepCopy();
        }
        ObjectNode overlay = mapper.createObjectNode();
        overlay.put(OVERLAY_MARKER, OVERLAY_SCHEMA_VERSION);
        overlay.set("source", payload.deepCopy());
        ObjectNode resolution = overlay.putObject("resolution");
        resolution.set("fields", mapper.createObjectNode());
        return overlay;
    }

    private JsonNode sourcePayload(JsonNode payload) {
        if (payload.isObject() && payload.path(OVERLAY_MARKER).asInt(-1) == OVERLAY_SCHEMA_VERSION
                && payload.path("source").isObject()) return payload.path("source");
        return payload;
    }

    private JsonNode resolution(JsonNode payload) {
        JsonNode value = payload.path("resolution");
        return value.isObject() ? value : mapper.createObjectNode();
    }

    private JsonNode readPayload(String payload) {
        try {
            return mapper.readTree(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Staged Picks template payload is invalid", exception);
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Prepared Picks template could not be staged", exception);
        }
    }

    private String field(JsonNode fields, String field, JsonNode source, String sourceField) {
        JsonNode value = fields.path(field);
        if (value.isTextual()) return value.asString();
        return text(source, sourceField);
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) throw new BadRequestException("Template " + field + " is required");
        return value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asString() : null;
    }

    private LocalDate date(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BadRequestException("Template eligibility date is invalid");
        }
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }

    private void putDate(ObjectNode node, String field, LocalDate value) {
        if (value == null) node.putNull(field);
        else node.put(field, value.toString());
    }

    private String categorySourceId(int index) {
        return "template-category:" + index;
    }

    private String optionSourceId(int categoryIndex, int nomineeIndex) {
        return "template-option:" + categoryIndex + ":" + nomineeIndex;
    }

    private record EffectiveTemplate(TemplateImportDocument document,
                                     List<List<ImportTarget>> resolvedOptions,
                                     List<List<UUID>> optionItemIds,
                                     com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO creation) { }
}
