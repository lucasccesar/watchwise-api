package com.watchwise.watchwise_api.platformimport.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.watchwise.watchwise_api.content.dto.MediaReference;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ImportTarget.ContentTarget.class, name = "CONTENT"),
        @JsonSubTypes.Type(value = ImportTarget.PersonTarget.class, name = "PERSON")
})
public sealed interface ImportTarget {
    record ContentTarget(MediaReference content) implements ImportTarget {
        public ContentTarget {
            if (content == null) throw new IllegalArgumentException("Content reference is required");
        }
    }

    record PersonTarget(String personTmdbId, MediaReference contextContent) implements ImportTarget {
        public PersonTarget {
            if (personTmdbId == null || personTmdbId.isBlank()) {
                throw new IllegalArgumentException("Person TMDB id is required");
            }
        }
    }
}
