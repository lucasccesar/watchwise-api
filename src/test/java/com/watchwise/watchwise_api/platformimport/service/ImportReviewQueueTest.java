package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportReviewPolicy;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportReviewQueue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ImportReviewQueueTest {
    private final ImportReviewQueue queue = new ImportReviewQueue(
            new ImportReviewPolicy(new ObjectMapper()));

    @Test
    void unchangedPendingItemDoesNotCreateAnotherReviewRevision() {
        ImportJob job = ImportJob.builder().id(UUID.randomUUID()).reviewRequiredCount(0).reviewRevision(0).build();
        ImportItem item = ImportItem.builder()
                .id(UUID.randomUUID())
                .jobId(job.getId())
                .planGeneration(1)
                .normalizedKind(ImportRecordKind.LIST_ITEM)
                .sourceNamespace("trakt:review-queue-test")
                .objectKind("LIST_ITEM")
                .sourceId(UUID.randomUUID().toString())
                .ordinal(1)
                .outcome("NORMALIZATION_ISSUE")
                .issue("LIST_ITEM_DESCRIPTION_TOO_LONG: Edit description.")
                .payloadJson("{\"source\":{\"description\":\"Long description\"}}")
                .build();

        queue.synchronize(job, List.of(item), true);
        long firstRevision = job.getReviewRevision();
        queue.synchronize(job, List.of(item), true);

        assertThat(firstRevision).isEqualTo(1);
        assertThat(job.getReviewRevision()).isEqualTo(firstRevision);
        assertThat(job.getReviewRequiredCount()).isEqualTo(1);
    }

    @Test
    void visibleChangeToPendingItemCreatesANewerRevision() {
        ImportJob job = ImportJob.builder().id(UUID.randomUUID()).reviewRequiredCount(0).reviewRevision(0).build();
        ImportItem item = ImportItem.builder()
                .id(UUID.randomUUID())
                .jobId(job.getId())
                .planGeneration(1)
                .normalizedKind(ImportRecordKind.LIST_ITEM)
                .sourceNamespace("trakt:review-queue-test")
                .objectKind("LIST_ITEM")
                .sourceId(UUID.randomUUID().toString())
                .ordinal(1)
                .outcome("NORMALIZATION_ISSUE")
                .issue("LIST_ITEM_DESCRIPTION_TOO_LONG: Edit description.")
                .payloadJson("{\"source\":{\"description\":\"First long description\"}}")
                .build();

        queue.synchronize(job, List.of(item), true);
        long firstRevision = job.getReviewRevision();
        item.setPayloadJson("{\"source\":{\"description\":\"Updated long description\"}}");
        queue.synchronize(job, List.of(item), true);

        assertThat(job.getReviewRevision()).isEqualTo(firstRevision + 1);
        assertThat(job.getReviewRequiredCount()).isEqualTo(1);
        assertThat(item.getReviewRevision()).isEqualTo(firstRevision + 1);
    }
}
