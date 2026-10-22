package com.watchwise.watchwise_api.platformimport.model;

import com.watchwise.watchwise_api.content.entity.ContentType;

public record AliasQuery(Kind kind, String title, Integer year, ContentType contentType) {
    public enum Kind { CONTENT, PERSON }

    public AliasQuery {
        if (kind == null) throw new IllegalArgumentException("Alias query kind is required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("Alias query title is required");
        if (title.length() > 500) throw new IllegalArgumentException("Alias query title is too long");
        if (year != null && year < 1) throw new IllegalArgumentException("Alias query year must be positive");
        if (kind == Kind.PERSON && (year != null || contentType != null)) {
            throw new IllegalArgumentException("Person alias queries cannot include a year or content type");
        }
    }

    public AliasQuery(String title, Integer year, ContentType contentType) {
        this(Kind.CONTENT, title, year, contentType);
    }

    public static AliasQuery person(String name) {
        return new AliasQuery(Kind.PERSON, name, null, null);
    }
}
