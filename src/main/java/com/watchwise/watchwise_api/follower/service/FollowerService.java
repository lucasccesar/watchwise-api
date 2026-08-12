package com.watchwise.watchwise_api.follower.service;

import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface FollowerService {

    FollowStatus followUser(UUID followerId, UUID followedId);

    void unfollowUser(UUID followerId, UUID followedId);

    void acceptFollowRequest(UUID targetUserId, UUID requesterId);

    void rejectFollowRequest(UUID targetUserId, UUID requesterId);

    Page<PublicUserDTO> getFollowers(UUID viewerId, UUID targetUserId, Integer pageNumber, Integer pageSize);

    Page<PublicUserDTO> getFollowing(UUID viewerId, UUID targetUserId, Integer pageNumber, Integer pageSize);

    Page<PublicUserDTO> getPendingFollowRequests(UUID targetUserId, Integer pageNumber, Integer pageSize);

}