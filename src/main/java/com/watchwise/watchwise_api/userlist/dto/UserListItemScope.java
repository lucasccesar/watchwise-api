package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.util.Set;
import java.util.stream.Collectors;

public enum UserListItemScope {
    MOVIE_OR_SERIES, SEASON, EPISODE, LIST, MIXED;

    public static UserListItemScope forContentType(ContentType type) {
        return switch (type) {
            case MOVIE, SERIES -> MOVIE_OR_SERIES;
            case SEASON -> SEASON;
            case EPISODE -> EPISODE;
        };
    }

    public static UserListItemScope resolve(Set<ContentType> distinctTypes, boolean hasNestedLists) {
        if (hasNestedLists) {
            return LIST;
        }
        if (distinctTypes.isEmpty()) {
            return null;
        }
        Set<UserListItemScope> groups = distinctTypes.stream()
                .map(UserListItemScope::forContentType)
                .collect(Collectors.toSet());
        return groups.size() > 1 ? MIXED : groups.iterator().next();
    }
}
