package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.UserVisibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserVisibilityServiceImpl implements UserVisibilityService {

    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;

    @Override
    public void assertCanView(UUID viewerId, UUID targetUserId) {
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        if (targetUserId.equals(viewerId) || Boolean.TRUE.equals(targetUser.getIsProfilePublic())) {
            return;
        }

        boolean viewerFollowsTarget = followerRepository
                .existsByFollowerIdAndFollowedIdAndStatus(viewerId, targetUserId, FollowStatus.ACCEPTED);
        if (!viewerFollowsTarget) {
            throw new ForbiddenException("This user profile is private");
        }
    }
}
