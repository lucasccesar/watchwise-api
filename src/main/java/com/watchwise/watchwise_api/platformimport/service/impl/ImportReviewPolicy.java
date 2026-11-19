package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.dto.ImportEditableFieldDTO;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Component
public class ImportReviewPolicy {
    private final ObjectMapper objectMapper;

    public ImportReviewPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ReviewState classify(ImportItem item) {
        if (item == null) return ReviewState.none();

        ImportDecision decision = item.getDecision();
        if (decision == ImportDecision.SKIP_ITEM || decision == ImportDecision.REMOVE_NOMINEE
                || decision == ImportDecision.REMOVE_CATEGORY) {
            return ReviewState.none();
        }

        if (candidateEnvelope(item.getResolvedTargetJson())) {
            if (decision == ImportDecision.RESOLVE_TARGET || decision == ImportDecision.REMOVE_NOMINEE) {
                return ReviewState.none();
            }
            if (item.getNormalizedKind() == ImportRecordKind.TEMPLATE_OPTION) {
                return new ReviewState(true, List.of(ImportDecision.RESOLVE_TARGET,
                        ImportDecision.REMOVE_NOMINEE, ImportDecision.SKIP_ITEM), List.of());
            }
            return new ReviewState(true, List.of(ImportDecision.RESOLVE_TARGET, ImportDecision.SKIP_ITEM), List.of());
        }

        String issue = item.getIssue() == null ? "" : item.getIssue();
        if ((issue.startsWith("PASS_ASSIGNMENT_REQUIRED") || issue.startsWith("PASS_ASSIGNMENT_CONFLICT")
                || issue.startsWith("MATCH_REQUIRED: PASS_ASSIGNMENT_"))
                && item.getNormalizedKind() == ImportRecordKind.DIARY_EVENT) {
            return state(List.of(ImportDecision.ASSIGN_PASS, ImportDecision.SKIP_ITEM), List.of());
        }

        boolean templateOptionNeedsChoice = item.getNormalizedKind() == ImportRecordKind.TEMPLATE_OPTION;
        if (!"NORMALIZATION_ISSUE".equals(item.getOutcome())
                || (!templateOptionNeedsChoice
                && (decision == ImportDecision.SKIP_ITEM || decision == ImportDecision.RESOLVE_TARGET))) {
            return ReviewState.none();
        }

        return normalizationIssueState(item);
    }

    private ReviewState normalizationIssueState(ImportItem item) {
        String issue = item.getIssue() == null ? "" : item.getIssue();
        if (item.getNormalizedKind() == ImportRecordKind.TEMPLATE_OPTION) {
            return state(List.of(ImportDecision.RESOLVE_TARGET, ImportDecision.REMOVE_NOMINEE,
                    ImportDecision.SKIP_ITEM), List.of());
        }
        if (issue.startsWith("LIST_ITEM_DESCRIPTION_TOO_LONG")) {
            return state(List.of(ImportDecision.EDIT_FIELD, ImportDecision.SKIP_ITEM),
                    editable(ImportResolutionOperation.EditableField.DESCRIPTION, 400));
        }
        if (issue.startsWith("LIST_METADATA_FIELDS_TOO_LONG")) {
            return state(List.of(ImportDecision.EDIT_FIELD, ImportDecision.SKIP_ITEM),
                    editable(ImportResolutionOperation.EditableField.TITLE, 255,
                            ImportResolutionOperation.EditableField.DESCRIPTION, 400));
        }
        if ("NEEDS_LIST_NAME".equals(issue)
                && item.getNormalizedKind() == ImportRecordKind.LIST_METADATA) {
            return state(List.of(ImportDecision.EDIT_FIELD, ImportDecision.SKIP_ITEM),
                    editable(ImportResolutionOperation.EditableField.TITLE, 255));
        }
        if (issue.startsWith(ImportedReviewTextNormalizer.REVIEW_TOO_LONG_ISSUE)) {
            return state(List.of(ImportDecision.EDIT_FIELD, ImportDecision.SKIP_ITEM),
                    editable(ImportResolutionOperation.EditableField.REVIEW,
                            ImportedReviewTextNormalizer.MAX_REVIEW_LENGTH));
        }
        if (issue.startsWith("LATEST_REVIEW_AMBIGUOUS")) {
            return state(List.of(ImportDecision.EDIT_FIELD, ImportDecision.SKIP_ITEM),
                    editable(ImportResolutionOperation.EditableField.WATCHED_DATE, null));
        }
        if (issue.startsWith("OPINION_EVIDENCE_PENDING")) {
            if ("deferred_opinion_replay".equals(item.getObjectKind())
                    && hasUsableWatchScopePreview(item)) {
                return state(List.of(ImportDecision.CONFIRM_WATCH_SCOPE, ImportDecision.SKIP_ITEM), List.of());
            }
            return state(List.of(ImportDecision.SKIP_ITEM), List.of());
        }
        if (issue.startsWith("WATCH_SCOPE_")) {
            if (item.getNormalizedKind() == ImportRecordKind.SERIES_EXPANSION
                    || !hasUsableWatchScopePreview(item)) {
                return state(List.of(ImportDecision.SKIP_ITEM), List.of());
            }
            return state(List.of(ImportDecision.CONFIRM_WATCH_SCOPE, ImportDecision.SKIP_ITEM), List.of());
        }
        if (issue.startsWith("TEMPLATE_ELIGIBILITY_PERIOD_INVALID")) {
            return state(List.of(ImportDecision.EDIT_FIELD, ImportDecision.SKIP_ITEM),
                    editable(ImportResolutionOperation.EditableField.ELIGIBILITY_START, null,
                            ImportResolutionOperation.EditableField.ELIGIBILITY_END, null));
        }
        if (issue.startsWith("SERIES_PASS_CONFLICT")
                || issue.startsWith("SERIES_CHILD_SKIPPED")
                || issue.startsWith("SERIES_CHILD_EVIDENCE_MISSING")) {
            return state(List.of(ImportDecision.SKIP_ITEM), List.of());
        }
        if (item.getDecision() != null) {
            return state(List.of(item.getDecision(), ImportDecision.SKIP_ITEM), List.of());
        }
        return state(List.of(ImportDecision.SKIP_ITEM), List.of());
    }

    private ReviewState state(List<ImportDecision> operations,
                              List<ImportEditableFieldDTO> fields) {
        return new ReviewState(true, operations, fields);
    }

    private List<ImportEditableFieldDTO> editable(ImportResolutionOperation.EditableField first,
                                                 Integer firstMaxLength) {
        return List.of(new ImportEditableFieldDTO(first, firstMaxLength));
    }

    private List<ImportEditableFieldDTO> editable(ImportResolutionOperation.EditableField first,
                                                 Integer firstMaxLength,
                                                 ImportResolutionOperation.EditableField second,
                                                 Integer secondMaxLength) {
        return List.of(new ImportEditableFieldDTO(first, firstMaxLength),
                new ImportEditableFieldDTO(second, secondMaxLength));
    }

    private boolean candidateEnvelope(String serialized) {
        if (serialized == null || serialized.isBlank()) return false;
        try {
            JsonNode node = objectMapper.readTree(serialized);
            return node.isObject() && node.has("schemaVersion") && node.has("candidates");
        } catch (JacksonException exception) {
            return false;
        }
    }

    private boolean hasUsableWatchScopePreview(ImportItem item) {
        if (item.getResolvedTargetJson() == null || item.getResolvedTargetJson().isBlank()
                || item.getPayloadJson() == null || item.getPayloadJson().isBlank()) return false;
        try {
            JsonNode preview = objectMapper.readTree(item.getPayloadJson())
                    .path("resolution").path("watchScopePreview");
            JsonNode episodes = preview.path("episodes");
            return preview.path("available").asBoolean(false)
                    && preview.path("scheduleVersion").isTextual()
                    && !preview.path("scheduleVersion").asString().isBlank()
                    && episodes.isArray() && !episodes.isEmpty();
        } catch (JacksonException exception) {
            return false;
        }
    }

    public record ReviewState(boolean pending, List<ImportDecision> allowedOperations,
                              List<ImportEditableFieldDTO> editableFields) {
        public ReviewState {
            allowedOperations = List.copyOf(allowedOperations);
            editableFields = List.copyOf(editableFields);
        }

        public static ReviewState none() {
            return new ReviewState(false, List.of(), List.of());
        }
    }
}
