package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.service.impl.WatchedSummaryReconciler;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WatchedSummaryReconcilerTest {

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final WatchedSummaryReconciler reconciler = new WatchedSummaryReconciler(mapper);
    private final ImportExecutionContext context = new ImportExecutionContext(
            OWNER_ID, JOB_ID, 1, 1, "trakt", UUID.randomUUID());

    @Test
    void partialEpisodeHistoryCoversTheShowSummaryWithoutExpandingTheSeries() throws Exception {
        NormalizedImportRecord episode = record("history:episode-1", ImportRecordKind.DIARY_EVENT,
                MediaReference.episode("42", 1, 1), "{}");
        NormalizedImportRecord summary = record("summary:show-42", ImportRecordKind.WATCHED_SUMMARY,
                MediaReference.series("42"), "{\"declaredPlays\":1,\"airedEpisodeCount\":84}");

        var decisions = reconciler.reconcile(context, List.of(episode), List.of(summary));

        assertThat(decisions).singleElement().satisfies(decision -> {
            assertThat(decision.disposition())
                    .isEqualTo(WatchedSummaryReconciler.Disposition.COVERED_BY_DETAIL_HISTORY);
            assertThat(decision.assertion()).isNull();
        });
    }

    @Test
    void summaryWithoutDetailHistoryPreservesItsUnknownCoverageDeclaration() throws Exception {
        NormalizedImportRecord summary = record("summary:show-42", ImportRecordKind.WATCHED_SUMMARY,
                MediaReference.series("42"),
                "{\"declaredPlays\":2,\"declaredLastWatchedOn\":\"2025-06-03\"}");

        var decisions = reconciler.reconcile(context, List.of(), List.of(summary));

        assertThat(decisions).singleElement().satisfies(decision -> {
            assertThat(decision.disposition())
                    .isEqualTo(WatchedSummaryReconciler.Disposition.STORE_UNKNOWN_ASSERTION);
            assertThat(decision.assertion().declaredPlays()).isEqualTo(2);
            assertThat(decision.assertion().declaredDate()).isEqualTo(LocalDate.of(2025, 6, 3));
            assertThat(decision.assertion().coverage()).isEqualTo("UNKNOWN");
        });
    }

    @Test
    void unrelatedEpisodeDoesNotCoverAShowSummary() throws Exception {
        NormalizedImportRecord unrelatedEpisode = record("history:episode-1", ImportRecordKind.DIARY_EVENT,
                MediaReference.episode("99", 1, 1), "{}");
        NormalizedImportRecord summary = record("summary:show-42", ImportRecordKind.WATCHED_SUMMARY,
                MediaReference.series("42"), "{}");

        var decisions = reconciler.reconcile(context, List.of(unrelatedEpisode), List.of(summary));

        assertThat(decisions).singleElement().satisfies(decision ->
                assertThat(decision.disposition())
                        .isEqualTo(WatchedSummaryReconciler.Disposition.STORE_UNKNOWN_ASSERTION));
    }

    private NormalizedImportRecord record(String id, ImportRecordKind kind, MediaReference media, String json)
            throws Exception {
        return new NormalizedImportRecord(id, kind, new ImportSourceKey("trakt", kind.name(), id), null,
                new ImportTarget.ContentTarget(media), mapper.readTree(json), 0);
    }
}
