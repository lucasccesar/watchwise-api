package com.watchwise.watchwise_api.content.dto;

import java.util.Set;

public record WatchlistEligibility(boolean allowed, String reason, Set<String> evidence) {

    public WatchlistEligibility {
        evidence = evidence == null ? Set.of() : Set.copyOf(evidence);
    }
}
