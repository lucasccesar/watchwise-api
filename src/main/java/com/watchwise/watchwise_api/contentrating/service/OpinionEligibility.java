package com.watchwise.watchwise_api.contentrating.service;

public record OpinionEligibility(
        boolean anyWatchedEvidence,
        boolean completeNow,
        boolean previouslyEligible) {
}
