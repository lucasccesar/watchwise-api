package com.watchwise.watchwise_api.follower.dto;

import com.watchwise.watchwise_api.follower.entity.FollowStatus;

public record FollowStatusResponseDTO(
        FollowStatus status
) {
}