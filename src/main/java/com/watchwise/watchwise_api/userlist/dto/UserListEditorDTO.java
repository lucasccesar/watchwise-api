package com.watchwise.watchwise_api.userlist.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserListEditorDTO(
        Status status,
        String name,
        String description,
        UserListVisibility visibility,
        UserListItemScope itemScope,
        List<Item> items) {

    public enum Status { READY, READ_ONLY, LEGACY }

    public static UserListEditorDTO withoutItems(Status status) {
        return new UserListEditorDTO(status, null, null, null, null, null);
    }

    public record Item(UUID id, Integer position, Card card, String customPosterUrl) {}

    public record Card(
            ContentType type, String tmdbId, String seriesTmdbId, Integer seasonNumber,
            Integer episodeNumber, String title, Integer releaseYear, String posterPath) {}
}
