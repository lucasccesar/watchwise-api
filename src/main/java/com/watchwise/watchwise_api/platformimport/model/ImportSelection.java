package com.watchwise.watchwise_api.platformimport.model;

import tools.jackson.databind.JsonNode;

import java.util.Set;

public record ImportSelection(boolean history, boolean watchlist, Set<String> listIds, JsonNode metadata) {
    public ImportSelection {
        listIds = listIds == null ? Set.of() : Set.copyOf(listIds);
        metadata = metadata == null ? null : metadata.deepCopy();
    }
}
