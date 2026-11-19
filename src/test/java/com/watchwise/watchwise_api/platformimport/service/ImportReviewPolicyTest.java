package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportReviewPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ImportReviewPolicyTest {
    private final ImportReviewPolicy policy = new ImportReviewPolicy(new ObjectMapper());

    @Test
    void offersResolveAndSkipForAnAmbiguousCandidate() {
        ImportItem item = item(ImportRecordKind.DIARY_EVENT, "AMBIGUOUS", null,
                "{\"schemaVersion\":1,\"candidates\":[{\"candidateId\":\"a\"}]}", null);

        ImportReviewPolicy.ReviewState result = policy.classify(item);

        assertThat(result.pending()).isTrue();
        assertThat(result.allowedOperations()).containsExactly(
                ImportDecision.RESOLVE_TARGET, ImportDecision.SKIP_ITEM);
    }

    @Test
    void keepsTraktWatchedSummaryNoticeOutOfTheReviewQueue() {
        ImportItem item = item(ImportRecordKind.WATCHED_SUMMARY, "MATCHED", null,
                "{\"kind\":\"CONTENT\"}", "No detailed watch event was found.");

        ImportReviewPolicy.ReviewState result = policy.classify(item);

        assertThat(result.pending()).isFalse();
        assertThat(result.allowedOperations()).isEmpty();
    }

    @Test
    void offersEditAndSkipForAnOversizedListDescription() {
        ImportItem item = item(ImportRecordKind.LIST_ITEM, "NORMALIZATION_ISSUE", null,
                "{\"kind\":\"CONTENT\"}", "LIST_ITEM_DESCRIPTION_TOO_LONG: Edit description.");

        ImportReviewPolicy.ReviewState result = policy.classify(item);

        assertThat(result.pending()).isTrue();
        assertThat(result.allowedOperations()).containsExactly(
                ImportDecision.EDIT_FIELD, ImportDecision.SKIP_ITEM);
        assertThat(result.editableFields()).singleElement().satisfies(field ->
                assertThat(field.toString()).contains("DESCRIPTION").contains("400"));
    }

    @Test
    void removesAnItemFromReviewAfterTheUserHasChosenToSkipIt() {
        ImportItem item = item(ImportRecordKind.DIARY_EVENT, "NORMALIZATION_ISSUE",
                ImportDecision.SKIP_ITEM, null, "The user skipped this item.");

        assertThat(policy.classify(item).pending()).isFalse();
    }

    @Test
    void offersOnlySkipForOpinionEvidenceThatHasNoWatchScopePreview() {
        ImportItem item = item(ImportRecordKind.REVIEW, "NORMALIZATION_ISSUE", null,
                "{\"kind\":\"CONTENT\",\"content\":{\"type\":\"MOVIE\",\"tmdbId\":\"1\"}}",
                "OPINION_EVIDENCE_PENDING: A matching diary event is required.");

        ImportReviewPolicy.ReviewState result = policy.classify(item);

        assertThat(result.pending()).isTrue();
        assertThat(result.allowedOperations()).containsExactly(ImportDecision.SKIP_ITEM);
    }

    @Test
    void offersWatchScopeConfirmationForDeferredOpinionWithUsableSnapshot() {
        ImportItem item = item(ImportRecordKind.REVIEW, "deferred_opinion_replay", "NORMALIZATION_ISSUE", null,
                "{\"kind\":\"CONTENT\",\"content\":{\"type\":\"SERIES\",\"tmdbId\":\"1\"}}",
                "OPINION_EVIDENCE_PENDING: A complete watched pass is required.");
        item.setPayloadJson("""
                {"resolution":{"watchScopePreview":{"available":true,"scheduleVersion":"v1","episodes":[{"seasonNumber":1,"episodeNumber":1}]}}}
                """);

        assertThat(policy.classify(item).allowedOperations()).containsExactly(
                ImportDecision.CONFIRM_WATCH_SCOPE, ImportDecision.SKIP_ITEM);
    }

    @Test
    void offersWatchScopeConfirmationOnlyWhenAUsableSnapshotIsAvailable() {
        ImportItem unavailable = item(ImportRecordKind.WATCHED_SUMMARY, "NORMALIZATION_ISSUE", null,
                "{\"kind\":\"CONTENT\",\"content\":{\"type\":\"SERIES\",\"tmdbId\":\"1\"}}",
                "WATCH_SCOPE_SCHEDULE_UNAVAILABLE");
        unavailable.setPayloadJson("""
                {"resolution":{"watchScopePreview":{"available":false,"episodes":[]}}}
                """);
        ImportItem available = item(ImportRecordKind.WATCHED_SUMMARY, "NORMALIZATION_ISSUE", null,
                "{\"kind\":\"CONTENT\",\"content\":{\"type\":\"SERIES\",\"tmdbId\":\"1\"}}",
                "WATCH_SCOPE_SCHEDULE_CHANGED: Refresh and confirm again.");
        available.setPayloadJson("""
                {"resolution":{"watchScopePreview":{"available":true,"scheduleVersion":"v1","episodes":[{"seasonNumber":1,"episodeNumber":1}]}}}
                """);

        assertThat(policy.classify(unavailable).allowedOperations())
                .containsExactly(ImportDecision.SKIP_ITEM);
        assertThat(policy.classify(available).allowedOperations())
                .containsExactly(ImportDecision.CONFIRM_WATCH_SCOPE, ImportDecision.SKIP_ITEM);
    }

    @Test
    void offersTitleCorrectionWhenAListNameIsMissing() {
        ImportItem item = item(ImportRecordKind.LIST_METADATA, "NORMALIZATION_ISSUE", null,
                null, "NEEDS_LIST_NAME");

        ImportReviewPolicy.ReviewState result = policy.classify(item);

        assertThat(result.allowedOperations()).containsExactly(
                ImportDecision.EDIT_FIELD, ImportDecision.SKIP_ITEM);
        assertThat(result.editableFields()).singleElement().satisfies(field ->
                assertThat(field.toString()).contains("TITLE").contains("255"));
    }

    @Test
    void offersPassAssignmentForALateMatchedDiaryEvent() {
        ImportItem item = item(ImportRecordKind.DIARY_EVENT, "MATCHED", ImportDecision.ASSIGN_PASS,
                "{\"kind\":\"CONTENT\",\"content\":{\"type\":\"MOVIE\",\"tmdbId\":\"1\"}}",
                "PASS_ASSIGNMENT_REQUIRED: Choose a viewing pass.");

        ImportReviewPolicy.ReviewState result = policy.classify(item);

        assertThat(result.pending()).isTrue();
        assertThat(result.allowedOperations()).containsExactly(
                ImportDecision.ASSIGN_PASS, ImportDecision.SKIP_ITEM);
    }

    @Test
    void recognizesPassConflictMessagesWithThePlannerDecisionPrefix() {
        ImportItem item = item(ImportRecordKind.DIARY_EVENT, "MATCHED", ImportDecision.ASSIGN_PASS,
                "{\"kind\":\"CONTENT\",\"content\":{\"type\":\"MOVIE\",\"tmdbId\":\"1\"}}",
                "MATCH_REQUIRED: PASS_ASSIGNMENT_CONFLICT: Assign an existing pass or skip.");

        assertThat(policy.classify(item).allowedOperations()).containsExactly(
                ImportDecision.ASSIGN_PASS, ImportDecision.SKIP_ITEM);
    }

    private ImportItem item(ImportRecordKind kind, String outcome, ImportDecision decision,
                            String resolvedTarget, String issue) {
        return item(kind, kind.name(), outcome, decision, resolvedTarget, issue);
    }

    private ImportItem item(ImportRecordKind kind, String objectKind, String outcome, ImportDecision decision,
                            String resolvedTarget, String issue) {
        return ImportItem.builder()
                .id(UUID.randomUUID())
                .jobId(UUID.randomUUID())
                .planGeneration(1)
                .normalizedKind(kind)
                .sourceNamespace("trakt:review-policy-test")
                .objectKind(objectKind)
                .sourceId(UUID.randomUUID().toString())
                .ordinal(1)
                .outcome(outcome)
                .decision(decision)
                .resolvedTargetJson(resolvedTarget)
                .issue(issue)
                .build();
    }
}
