package com.watchwise.watchwise_api.pickstemplate.entity;

import com.watchwise.watchwise_api.content.entity.ContentType;

public enum PickAllowedType {
    MOVIE, SERIES, EPISODE, PERSON;

    public ContentType toContentType() {
        return switch (this) {
            case MOVIE -> ContentType.MOVIE;
            case SERIES -> ContentType.SERIES;
            case EPISODE -> ContentType.EPISODE;
            case PERSON -> throw new IllegalStateException("Person does not have a content type");
        };
    }
}
