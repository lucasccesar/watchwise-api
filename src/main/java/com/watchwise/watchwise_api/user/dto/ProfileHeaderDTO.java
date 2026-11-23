package com.watchwise.watchwise_api.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.userblock.entity.UserBlockRelationship;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfileHeaderDTO(
        UUID id,
        String username,
        String name,
        Access access,
        String description,
        String profilePicture,
        String banner,
        Boolean isProfilePublic,
        Long followersCount,
        Long followingCount,
        Long minutesWatchedMoviesLast30Days,
        Long minutesWatchedEpisodesLast30Days,
        Boolean ownedByViewer,
        FollowStatus viewerFollowStatus,
        Boolean followsViewer,
        UserBlockRelationship blockRelationship
) {
    public enum Access {
        VISIBLE, PRIVATE, UNAVAILABLE
    }
}

