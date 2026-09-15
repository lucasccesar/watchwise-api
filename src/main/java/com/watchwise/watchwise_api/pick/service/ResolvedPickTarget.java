package com.watchwise.watchwise_api.pick.service;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;

public record ResolvedPickTarget(
        Content content,
        String personTmdbId,
        Content contextContent,
        TargetKey key) {

    public record TargetKey(ContentKey content, String personTmdbId, ContentKey contextContent) {
    }

    public record ContentKey(
            ContentType type,
            String tmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber) {
    }
}
