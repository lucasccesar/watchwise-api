package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListCardDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;
import com.watchwise.watchwise_api.userlist.dto.UserListPreviewDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;

import java.util.*;

final class UserListViewAssembler {

    private static final int PREVIEW_LIMIT = 5;

    private UserListViewAssembler() {
    }

    static List<UserListCardDTO> assemble(
            List<UserList> lists,
            Map<UUID, UserPreviewDTO> ownerPreviews,
            Map<UUID, List<ContentCardDTO>> contentCards,
            Map<UUID, List<UserListPreviewDTO>> nestedListPreviews,
            Map<UUID, Long> nestedListsCounts,
            Map<UUID, Long> likesCounts,
            Map<UUID, Long> itemsCounts,
            Map<UUID, Long> commentsCounts,
            Set<UUID> likedListIds,
            Map<UUID, UserListItemScope> itemScopes,
            UUID viewerId) {
        if (lists == null || lists.isEmpty()) {
            return List.of();
        }
        return lists.stream().map(list -> toCard(list, ownerPreviews, contentCards, nestedListPreviews,
                nestedListsCounts, likesCounts, itemsCounts, commentsCounts, likedListIds, itemScopes, viewerId)).toList();
    }

    private static UserListCardDTO toCard(
            UserList list,
            Map<UUID, UserPreviewDTO> ownerPreviews,
            Map<UUID, List<ContentCardDTO>> contentCards,
            Map<UUID, List<UserListPreviewDTO>> nestedListPreviews,
            Map<UUID, Long> nestedListsCounts,
            Map<UUID, Long> likesCounts,
            Map<UUID, Long> itemsCounts,
            Map<UUID, Long> commentsCounts,
            Set<UUID> likedListIds,
            Map<UUID, UserListItemScope> itemScopes,
            UUID viewerId) {
        UUID id = list.getId();
        UUID ownerId = list.getUser() == null ? null : list.getUser().getId();
        return new UserListCardDTO(id, get(ownerPreviews, ownerId), list.getName(), list.getDescription(),
                list.getVisibility(), list.getCreatedAt(), list.getUpdatedAt(),
                limited(get(contentCards, id)), limited(get(nestedListPreviews, id)),
                count(nestedListsCounts, id), count(likesCounts, id),
                likedListIds != null && likedListIds.contains(id), count(itemsCounts, id),
                count(commentsCounts, id), list.getRank(), get(itemScopes, id),
                viewerId != null && viewerId.equals(ownerId));
    }

    private static <T> T get(Map<UUID, T> values, UUID key) {
        return values == null || key == null ? null : values.get(key);
    }

    private static long count(Map<UUID, Long> values, UUID key) {
        Long value = get(values, key);
        return value == null ? 0L : value;
    }

    private static <T> List<T> limited(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(values.subList(0, Math.min(PREVIEW_LIMIT, values.size())));
    }
}
