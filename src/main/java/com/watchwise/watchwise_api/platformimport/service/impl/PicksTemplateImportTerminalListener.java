package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportUnitTerminalListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PicksTemplateImportTerminalListener implements ImportUnitTerminalListener {
    private final ImportItemRepository items;

    public PicksTemplateImportTerminalListener(ImportItemRepository items) {
        this.items = items;
    }

    @Override
    public void onTerminal(ImportExecutionContext context, ImportItem item) {
        if (item.getNormalizedKind() != ImportRecordKind.TEMPLATE) return;
        List<ImportItem> categories = items.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                context.jobId(), context.planGeneration(), item.getSourceId(), ImportRecordKind.TEMPLATE_CATEGORY);
        if (categories.isEmpty()) return;

        List<String> categoryIds = categories.stream().map(ImportItem::getSourceId).toList();
        List<ImportItem> children = new ArrayList<>(categories);
        children.addAll(items.findAllByJobIdAndPlanGenerationAndParentSourceIdIn(
                context.jobId(), context.planGeneration(), categoryIds));

        boolean applied = "APPLIED".equals(item.getOutcome());
        for (ImportItem child : children) {
            child.setOutcome(applied ? "APPLIED" : "SKIPPED");
            child.setIssue(null);
        }
        items.saveAll(children);
    }
}
