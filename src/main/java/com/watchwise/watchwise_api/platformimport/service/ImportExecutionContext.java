package com.watchwise.watchwise_api.platformimport.service;

import java.util.UUID;

public record ImportExecutionContext(
        UUID ownerId,
        UUID jobId,
        long previewVersion,
        long planGeneration,
        String sourceNamespace,
        UUID leaseToken,
        ImportApplyProgress applyProgress) {

    public ImportExecutionContext(UUID ownerId, UUID jobId, long previewVersion, long planGeneration,
                                  String sourceNamespace, UUID leaseToken) {
        this(ownerId, jobId, previewVersion, planGeneration, sourceNamespace, leaseToken, null);
    }

    public ImportExecutionContext {
        if (ownerId == null || jobId == null || previewVersion < 0 || planGeneration < 0
                || sourceNamespace == null || sourceNamespace.isBlank() || leaseToken == null) {
            throw new IllegalArgumentException("Import execution context is invalid");
        }
    }

    public ImportExecutionContext withApplyProgress(ImportApplyProgress progress) {
        return new ImportExecutionContext(ownerId, jobId, previewVersion, planGeneration,
                sourceNamespace, leaseToken, progress);
    }

    public void transitionOutcome(com.watchwise.watchwise_api.platformimport.entity.ImportItem item,
                                  String outcome) {
        validateApplyItem(item);
        if (applyProgress == null) item.setOutcome(outcome);
        else applyProgress.transition(item, outcome);
    }

    public void stageApplyItem(com.watchwise.watchwise_api.platformimport.entity.ImportItem item) {
        validateApplyItem(item);
        if (applyProgress != null) applyProgress.stage(item);
    }

    private void validateApplyItem(com.watchwise.watchwise_api.platformimport.entity.ImportItem item) {
        if (item == null || !jobId.equals(item.getJobId()) || planGeneration != item.getPlanGeneration()) {
            throw new IllegalArgumentException("Import item does not belong to the active plan generation");
        }
    }
}
