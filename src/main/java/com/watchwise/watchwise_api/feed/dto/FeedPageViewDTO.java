package com.watchwise.watchwise_api.feed.dto;

import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.util.List;

public record FeedPageViewDTO(
        List<FeedItemViewDTO> content,
        int size,
        String nextCursor,
        boolean hasNext,
        long followingCount,
        List<UserPreviewDTO> followedUsersPreview) {

    public FeedPageViewDTO {
        content = content == null ? List.of() : List.copyOf(content);
        followedUsersPreview = followedUsersPreview == null ? List.of() : List.copyOf(followedUsersPreview);
    }
}
