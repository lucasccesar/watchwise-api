package com.watchwise.watchwise_api.platformimport.parser;

import java.util.List;

public record ParsedListMetadata(String name, String description, List<String> tags, String sourceUri) {
    public ParsedListMetadata {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
