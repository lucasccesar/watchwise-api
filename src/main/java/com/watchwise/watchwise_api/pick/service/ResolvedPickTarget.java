package com.watchwise.watchwise_api.pick.service;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.pick.entity.PickSelection;

public record ResolvedPickTarget(
        Content content,
        String personTmdbId,
        Content contextContent,
        TargetKey key) {

    public static TargetKey keyOf(PickSelection selection) {
        return new TargetKey(keyOf(selection.getContent()), trim(selection.getPersonTmdbId()), keyOf(selection.getContextContent()));
    }

    private static ContentKey keyOf(Content content) {
        return content == null ? null : new ContentKey(content.getType(), trim(content.getTmdbId()),
                trim(content.getSeriesTmdbId()), content.getSeasonNumber(), content.getEpisodeNumber());
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

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
