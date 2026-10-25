package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.NormalizedHistoryPlan;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportedOpinionPlanner;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportedReviewTextNormalizer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.data.domain.Pageable;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class ImportedOpinionPlannerTest {

    @Test
    void mapsReviewAndRatingFieldsWithoutTurningRatingOnlyItemIntoAReview() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID lease = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID ratingId = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();
        ImportItemRepository repository = Mockito.mock(ImportItemRepository.class);
        var review = item(mapper, jobId, reviewId, ImportRecordKind.REVIEW, "review-1",
                "{\"review\":\"First<br>Second &amp; third\",\"score\":7,\"sourceRecordedOn\":\"2026-10-01\"}");
        var rating = item(mapper, jobId, ratingId, ImportRecordKind.CURRENT_RATING, "rating-1",
                "{\"score\":8,\"sourceRecordedOn\":\"2026-10-02\"}");
        when(repository.findHistoryRowsAfter(eq(jobId), eq(ownerId), eq(2L), anyCollection(), anyCollection(),
                eq(-1L), any(UUID.class), any())).thenReturn(List.of(review, rating));
        ImportedOpinionPlanner planner = new ImportedOpinionPlanner(repository, mapper,
                new ImportedReviewTextNormalizer());
        ImportExecutionContext context = new ImportExecutionContext(ownerId, jobId, 3, 2,
                "trakt-account-7", lease);

        var units = planner.nextBatch(context, new NormalizedHistoryPlan(jobId, 0, 0, 1, 1, 0), null, 50);

        assertThat(units).hasSize(2);
        assertThat(units.get(0).sourceKey()).isEqualTo(new ImportSourceKey("trakt-account-7", "review", "review-1"));
        assertThat(units.get(0).content()).isEqualTo(MediaReference.series("42"));
        assertThat(units.get(0).review().comment()).isEqualTo("First\nSecond & third");
        assertThat(units.get(0).review().scoreAtPublication()).isEqualTo(7);
        assertThat(units.get(0).initialScore()).isNull();
        assertThat(units.get(1).review()).isNull();
        assertThat(units.get(1).initialScore()).isEqualTo(8);
    }

    private ImportItem item(ObjectMapper mapper, UUID jobId, UUID itemId, ImportRecordKind kind,
                            String sourceId, String payload) throws Exception {
        return ImportItem.builder().id(itemId).jobId(jobId).planGeneration(2)
                .ordinal(kind == ImportRecordKind.REVIEW ? 0 : 1)
                .normalizedKind(kind).sourceNamespace("trakt-account-7").objectKind(kind.name().toLowerCase())
                .sourceId(sourceId).payloadJson(payload)
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(MediaReference.series("42"))))
                .outcome("MATCHED").build();
    }
}
