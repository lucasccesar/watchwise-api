package com.watchwise.watchwise_api.platformimport.model;

import com.watchwise.watchwise_api.content.dto.MediaReference;

import java.util.List;
import java.util.Set;

public record PreparedWatchlistImport(List<NormalizedImportRecord> items, Set<MediaReference> sourceWatched) {
    public PreparedWatchlistImport {
        if (items == null || items.size() > 200) {
            throw new IllegalArgumentException("Prepared watchlist import is limited to 200 items");
        }
        items = List.copyOf(items);
        sourceWatched = sourceWatched == null ? Set.of() : Set.copyOf(sourceWatched);
    }
}
