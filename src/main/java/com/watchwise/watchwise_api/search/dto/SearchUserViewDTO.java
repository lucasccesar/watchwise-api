package com.watchwise.watchwise_api.search.dto;

import com.watchwise.watchwise_api.follower.entity.FollowStatus;

import java.util.UUID;

public record SearchUserViewDTO(
        UUID id,
        String name,
        String username,
        String profilePicture,
        String description,
        boolean profileDetailsVisible,
        Long watchedCount,
        Long listsCount,
        Long followersCount,
        boolean ownedByViewer,
        FollowStatus viewerFollowStatus,
        boolean followsViewer) {
}
