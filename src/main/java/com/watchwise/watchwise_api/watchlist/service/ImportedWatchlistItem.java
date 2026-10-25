package com.watchwise.watchwise_api.watchlist.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;

import java.util.UUID;

public record ImportedWatchlistItem(UUID contentId, MediaReference content) {
    public ImportedWatchlistItem {
        if (contentId == null || content == null) {
            throw new IllegalArgumentException("Imported watchlist content and reference are required");
        }
    }
}
