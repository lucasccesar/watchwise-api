package com.watchwise.watchwise_api.platformimport.model;

import com.watchwise.watchwise_api.diaryentry.dto.WatchEventCommand;

import java.time.Instant;
import java.util.Map;
import java.util.List;

public record SeriesExpansionPlan(
        String parentKey,
        int targetPass,
        List<WatchEventCommand> children,
        String scopeHash,
        Instant selectedAt,
        Map<Integer, String> childPassIssues) {

    public SeriesExpansionPlan {
        if (parentKey == null || parentKey.isBlank() || targetPass < 1 || children == null || children.isEmpty()
                || scopeHash == null || scopeHash.isBlank() || selectedAt == null) {
            throw new IllegalArgumentException("Series expansion plan is incomplete");
        }
        children = List.copyOf(children);
        childPassIssues = childPassIssues == null ? Map.of() : Map.copyOf(childPassIssues);
        int childCount = children.size();
        if (childPassIssues.keySet().stream().anyMatch(index -> index < 0 || index >= childCount)) {
            throw new IllegalArgumentException("Series expansion pass issue index is outside the child list");
        }
    }
}
