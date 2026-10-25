package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class LetterboxdSeriesExpansionPreparer {
    private static final int BATCH_SIZE = 200;
    private static final List<String> PREVIEWABLE_OUTCOMES = List.of("MATCHED", "RESOLVED");

    private final ImportItemRepository itemRepository;
    private final LetterboxdSeriesExpansionPlanner planner;
    private final LetterboxdSeriesExpansionApplier applier;
    private final Clock clock;

    public LetterboxdSeriesExpansionPreparer(ImportItemRepository itemRepository,
                                             LetterboxdSeriesExpansionPlanner planner,
                                             LetterboxdSeriesExpansionApplier applier,
                                             Clock clock) {
        this.itemRepository = itemRepository;
        this.planner = planner;
        this.applier = applier;
        this.clock = clock;
    }

    public long prepare(ImportExecutionContext context) {
        long afterOrdinal = -1;
        UUID afterId = new UUID(0, 0);
        long issueCount = 0;
        while (true) {
            List<ImportItem> parents = itemRepository.findHistoryRowsAfter(context.jobId(), context.ownerId(),
                    context.planGeneration(), List.of(ImportRecordKind.SERIES_EXPANSION),
                    PREVIEWABLE_OUTCOMES, afterOrdinal, afterId, PageRequest.of(0, BATCH_SIZE));
            if (parents.isEmpty()) return issueCount;
            for (ImportItem parent : parents) {
                afterOrdinal = parent.getOrdinal();
                afterId = parent.getId();
                if (parent.getDecision() == ImportDecision.SKIP_ITEM || applier.hasPreparedPlan(parent)) continue;
                try {
                    var plan = planner.plan(context.ownerId(), parent, Instant.now(clock));
                    issueCount += applier.stageForPreview(context, parent.getId(), plan);
                } catch (LetterboxdSeriesExpansionPlanner.SeriesExpansionIssueException issue) {
                    applier.markPreviewIssue(context, parent.getId(), issue.getMessage());
                    issueCount++;
                }
            }
            if (parents.size() < BATCH_SIZE) return issueCount;
        }
    }
}
