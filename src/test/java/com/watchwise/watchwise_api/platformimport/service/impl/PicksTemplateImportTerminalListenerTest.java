package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.service.ImportApplyProgress;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PicksTemplateImportTerminalListenerTest {
    @Mock
    private ImportItemRepository items;

    @Test
    void countsEveryCategoryAndNomineeWhenTemplateCompletes() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ImportItem template = item(jobId, ImportRecordKind.TEMPLATE, "root", null, null);
        ImportItem category = item(jobId, ImportRecordKind.TEMPLATE_CATEGORY, "category", "root", "RESOLVED");
        ImportItem nominee = item(jobId, ImportRecordKind.TEMPLATE_OPTION, "nominee", "category", "RESOLVED");
        when(items.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                jobId, 1, "root", ImportRecordKind.TEMPLATE_CATEGORY)).thenReturn(List.of(category));
        when(items.findAllByJobIdAndPlanGenerationAndParentSourceIdIn(jobId, 1, List.of("category")))
                .thenReturn(List.of(nominee));

        ImportApplyProgress progress = new ImportApplyProgress();
        ImportExecutionContext context = new ImportExecutionContext(ownerId, jobId, 1, 1,
                "watchwise-template", UUID.randomUUID()).withApplyProgress(progress);
        context.transitionOutcome(template, "APPLIED");
        new PicksTemplateImportTerminalListener(items).onTerminal(context, template);

        ImportJob job = ImportJob.builder().progressTotalItems(3L)
                .progressAppliedItems(0L).progressSkippedItems(0L).build();
        progress.applyTo(job);

        assertThat(category.getOutcome()).isEqualTo("APPLIED");
        assertThat(nominee.getOutcome()).isEqualTo("APPLIED");
        assertThat(job.getProgressTotalItems()).isEqualTo(3);
        assertThat(job.getProgressAppliedItems()).isEqualTo(3);
        assertThat(job.getProgressSkippedItems()).isZero();
    }

    @Test
    void countsEveryCategoryAndNomineeWhenTemplateIsSkipped() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ImportItem template = item(jobId, ImportRecordKind.TEMPLATE, "root", null, null);
        ImportItem category = item(jobId, ImportRecordKind.TEMPLATE_CATEGORY, "category", "root", "RESOLVED");
        ImportItem nominee = item(jobId, ImportRecordKind.TEMPLATE_OPTION, "nominee", "category", "RESOLVED");
        when(items.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                jobId, 1, "root", ImportRecordKind.TEMPLATE_CATEGORY)).thenReturn(List.of(category));
        when(items.findAllByJobIdAndPlanGenerationAndParentSourceIdIn(jobId, 1, List.of("category")))
                .thenReturn(List.of(nominee));

        ImportApplyProgress progress = new ImportApplyProgress();
        ImportExecutionContext context = new ImportExecutionContext(ownerId, jobId, 1, 1,
                "watchwise-template", UUID.randomUUID()).withApplyProgress(progress);
        context.transitionOutcome(template, "SKIPPED");
        new PicksTemplateImportTerminalListener(items).onTerminal(context, template);

        ImportJob job = ImportJob.builder().progressTotalItems(3L)
                .progressAppliedItems(0L).progressSkippedItems(0L).build();
        progress.applyTo(job);

        assertThat(category.getOutcome()).isEqualTo("SKIPPED");
        assertThat(nominee.getOutcome()).isEqualTo("SKIPPED");
        assertThat(job.getProgressTotalItems()).isEqualTo(3);
        assertThat(job.getProgressAppliedItems()).isZero();
        assertThat(job.getProgressSkippedItems()).isEqualTo(3);
    }

    private ImportItem item(UUID jobId, ImportRecordKind kind, String sourceId,
                            String parentSourceId, String outcome) {
        return ImportItem.builder()
                .id(UUID.randomUUID())
                .jobId(jobId)
                .planGeneration(1)
                .normalizedKind(kind)
                .sourceNamespace("watchwise-template")
                .objectKind(kind.name())
                .sourceId(sourceId)
                .parentSourceId(parentSourceId)
                .outcome(outcome)
                .ordinal(0)
                .build();
    }
}
