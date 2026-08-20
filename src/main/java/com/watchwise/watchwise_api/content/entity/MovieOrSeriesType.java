package com.watchwise.watchwise_api.content.entity;

public enum MovieOrSeriesType {
    MOVIE,
    SERIES;

    public ContentType toContentType() {
        return ContentType.valueOf(name());
    }
}
