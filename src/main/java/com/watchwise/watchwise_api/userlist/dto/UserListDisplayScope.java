package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.util.Set;

public enum UserListDisplayScope {
    MOVIES,
    SERIES,
    MOVIES_AND_SERIES,
    SEASONS,
    EPISODES,
    MIXED,
    LISTS,
    EMPTY;

    public static UserListDisplayScope resolve(Set<ContentType> distinctTypes, boolean hasNestedLists) {
        if (hasNestedLists) {
            return LISTS;
        }
        if (distinctTypes == null || distinctTypes.isEmpty()) {
            return EMPTY;
        }
        if (distinctTypes.equals(Set.of(ContentType.MOVIE))) {
            return MOVIES;
        }
        if (distinctTypes.equals(Set.of(ContentType.SERIES))) {
            return SERIES;
        }
        if (distinctTypes.equals(Set.of(ContentType.MOVIE, ContentType.SERIES))) {
            return MOVIES_AND_SERIES;
        }
        if (distinctTypes.equals(Set.of(ContentType.SEASON))) {
            return SEASONS;
        }
        if (distinctTypes.equals(Set.of(ContentType.EPISODE))) {
            return EPISODES;
        }
        return MIXED;
    }
}
