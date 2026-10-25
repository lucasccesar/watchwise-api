package com.watchwise.watchwise_api.watchlist.service;

import java.util.UUID;

public record ImportedWatchlistWriteResult(UUID contentId, UUID entryId, Outcome outcome, String reason) {
    public enum Outcome { INSERTED, ALREADY_PRESENT, INELIGIBLE }
}
