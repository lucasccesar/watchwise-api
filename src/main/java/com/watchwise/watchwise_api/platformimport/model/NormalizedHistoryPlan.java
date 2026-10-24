package com.watchwise.watchwise_api.platformimport.model;

import java.util.UUID;

public record NormalizedHistoryPlan(
        UUID jobId,
        long eventCount,
        long markerCount,
        long ratingCount,
        long reviewCount,
        long issueCount) {

    public NormalizedHistoryPlan {
        if (jobId == null || eventCount < 0 || markerCount < 0 || ratingCount < 0
                || reviewCount < 0 || issueCount < 0) {
            throw new IllegalArgumentException("Normalized history plan counts and job are required");
        }
    }

    public static NormalizedHistoryPlan empty(UUID jobId) {
        return new NormalizedHistoryPlan(jobId, 0, 0, 0, 0, 0);
    }
}
