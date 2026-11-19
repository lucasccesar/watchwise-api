package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ImportApplyProgressTest {
    private final UUID jobId = UUID.randomUUID();
    private final long generation = 7;

    @Test
    void countsOnlyNewTerminalOutcomesAndKeepsTransitionsIdempotent() {
        ImportApplyProgress progress = new ImportApplyProgress();
        ImportItem item = item("MATCHED");

        progress.transition(item, "APPLIED");
        progress.transition(item, "APPLIED");

        assertThat(progress.appliedDelta()).isEqualTo(1);
        assertThat(progress.skippedDelta()).isZero();
        assertThat(progress.totalDelta()).isZero();
        assertThat(item.getOutcome()).isEqualTo("APPLIED");

        ImportJob job = job(1, 0, 0);
        progress.applyTo(job);

        assertThat(job.getProgressTotalItems()).isEqualTo(1);
        assertThat(job.getProgressAppliedItems()).isEqualTo(1);
    }

    @Test
    void removesAbsorbedItemsFromTheTotalAndAddsItemsStagedDuringApply() {
        ImportApplyProgress progress = new ImportApplyProgress();
        ImportItem absorbed = item(null);
        ImportItem stagedChild = item(null);

        progress.transition(absorbed, "ABSORBED");
        assertThat(progress.totalDelta()).isEqualTo(-1);
        progress.stage(stagedChild);

        assertThat(progress.totalDelta()).isZero();
        assertThat(progress.appliedDelta()).isZero();
        assertThat(progress.skippedDelta()).isZero();
        assertThat(stagedChild.getJobId()).isEqualTo(jobId);

        ImportJob job = job(4, 1, 1);
        progress.applyTo(job);

        assertThat(job.getProgressTotalItems()).isEqualTo(4);
        assertThat(job.getProgressAppliedItems()).isEqualTo(1);
        assertThat(job.getProgressSkippedItems()).isEqualTo(1);
    }

    private ImportItem item(String outcome) {
        return ImportItem.builder()
                .id(UUID.randomUUID())
                .jobId(jobId)
                .planGeneration(generation)
                .normalizedKind(ImportRecordKind.DIARY_EVENT)
                .sourceNamespace("test-account")
                .objectKind("diary")
                .sourceId(UUID.randomUUID().toString())
                .outcome(outcome)
                .ordinal(1)
                .build();
    }

    private ImportJob job(long total, long applied, long skipped) {
        return ImportJob.builder()
                .id(jobId)
                .progressTotalItems(total)
                .progressAppliedItems(applied)
                .progressSkippedItems(skipped)
                .build();
    }
}
