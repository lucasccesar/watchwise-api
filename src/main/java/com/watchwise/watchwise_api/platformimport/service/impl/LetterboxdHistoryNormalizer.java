package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.model.NormalizedHistoryPlan;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import org.springframework.stereotype.Component;

@Component
public class LetterboxdHistoryNormalizer {
    private final HistoryNormalizationSupport support;
    private final LetterboxdSeriesExpansionPreparer expansionPreparer;

    public LetterboxdHistoryNormalizer(HistoryNormalizationSupport support,
                                      LetterboxdSeriesExpansionPreparer expansionPreparer) {
        this.support = support;
        this.expansionPreparer = expansionPreparer;
    }

    public NormalizedHistoryPlan normalize(ImportExecutionContext context) {
        return support.normalize(context, false);
    }

    public NormalizedHistoryPlan normalize(ImportExecutionContext context, boolean historySelected) {
        NormalizedHistoryPlan normalized = support.normalize(context, false);
        if (!historySelected) return normalized;
        long expansionIssues = expansionPreparer.prepare(context);
        return new NormalizedHistoryPlan(normalized.jobId(), normalized.eventCount(), normalized.markerCount(),
                normalized.ratingCount(), normalized.reviewCount(), normalized.issueCount() + expansionIssues);
    }
}
