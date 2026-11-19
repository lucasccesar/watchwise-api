package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;

import java.util.Set;

public final class ImportApplyProgress {
    private static final Set<String> EXCLUDED_OUTCOMES = Set.of(
            "ABSORBED", "AUXILIARY_EVIDENCE", "AUXILIARY_UNRESOLVED");

    private long totalDelta;
    private long appliedDelta;
    private long skippedDelta;

    public void transition(ImportItem item, String nextOutcome) {
        if (item == null) throw new IllegalArgumentException("Import item is required");

        String currentOutcome = item.getOutcome();
        totalDelta = Math.addExact(totalDelta,
                totalContribution(nextOutcome) - totalContribution(currentOutcome));
        appliedDelta = Math.addExact(appliedDelta,
                outcomeContribution(nextOutcome, "APPLIED") - outcomeContribution(currentOutcome, "APPLIED"));
        skippedDelta = Math.addExact(skippedDelta,
                outcomeContribution(nextOutcome, "SKIPPED") - outcomeContribution(currentOutcome, "SKIPPED"));
        item.setOutcome(nextOutcome);
    }

    public void stage(ImportItem item) {
        if (item == null) throw new IllegalArgumentException("Import item is required");
        totalDelta = Math.addExact(totalDelta, totalContribution(item.getOutcome()));
    }

    public void applyTo(ImportJob job) {
        if (job == null) throw new IllegalArgumentException("Import job is required");
        job.adjustApplyProgress(totalDelta, appliedDelta, skippedDelta);
    }

    public long totalDelta() {
        return totalDelta;
    }

    public long appliedDelta() {
        return appliedDelta;
    }

    public long skippedDelta() {
        return skippedDelta;
    }

    private long totalContribution(String outcome) {
        return outcome == null || !EXCLUDED_OUTCOMES.contains(outcome) ? 1 : 0;
    }

    private long outcomeContribution(String outcome, String terminalOutcome) {
        return terminalOutcome.equals(outcome) ? 1 : 0;
    }
}
