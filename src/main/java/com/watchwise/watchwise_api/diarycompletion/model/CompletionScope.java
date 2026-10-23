package com.watchwise.watchwise_api.diarycompletion.model;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;

import java.util.Objects;
import java.util.Set;

public record CompletionScope(
        MediaReference reference,
        Set<EpisodeCoordinate> episodes,
        boolean releaseComplete) {

    public CompletionScope {
        Objects.requireNonNull(reference, "reference is required");
        Objects.requireNonNull(episodes, "episodes are required");
        if (reference.type() != ContentType.SERIES && reference.type() != ContentType.SEASON) {
            throw new IllegalArgumentException("Completion scopes require a series or season reference");
        }
        episodes = Set.copyOf(episodes);
        if (reference.type() == ContentType.SEASON
                && episodes.stream().anyMatch(episode -> episode.seasonNumber() != reference.seasonNumber())) {
            throw new IllegalArgumentException("Season completion scopes cannot contain episodes from another season");
        }
    }
}
