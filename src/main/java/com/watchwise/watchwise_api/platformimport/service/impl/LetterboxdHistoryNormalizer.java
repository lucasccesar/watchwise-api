package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.model.NormalizedHistoryPlan;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import org.springframework.stereotype.Component;

@Component
public class LetterboxdHistoryNormalizer {
    private final HistoryNormalizationSupport support;

    public LetterboxdHistoryNormalizer(HistoryNormalizationSupport support) {
        this.support = support;
    }

    public NormalizedHistoryPlan normalize(ImportExecutionContext context) {
        return support.normalize(context, false);
    }
}
