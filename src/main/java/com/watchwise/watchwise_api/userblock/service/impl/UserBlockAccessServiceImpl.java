package com.watchwise.watchwise_api.userblock.service.impl;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.userblock.entity.UserBlockRelationship;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserBlockAccessServiceImpl implements UserBlockAccessService {

    private final UserBlockRepository userBlockRepository;

    @Override
    public boolean isBlockedEitherDirection(UUID firstUserId, UUID secondUserId) {
        if (firstUserId.equals(secondUserId)) {
            return false;
        }
        return userBlockRepository.existsEitherDirection(firstUserId, secondUserId);
    }

    @Override
    public UserBlockRelationship relationship(UUID viewerId, UUID targetUserId) {
        if (viewerId.equals(targetUserId)) {
            return UserBlockRelationship.NONE;
        }

        List<UserBlockRepository.PairDirection> directions =
                userBlockRepository.findPairDirections(viewerId, targetUserId);
        boolean blockedByMe = directions.stream().anyMatch(direction -> direction.getBlockerId().equals(viewerId));
        boolean blockedMe = directions.stream().anyMatch(direction -> direction.getBlockerId().equals(targetUserId));
        if (blockedByMe && blockedMe) return UserBlockRelationship.MUTUAL;
        if (blockedByMe) return UserBlockRelationship.BLOCKED_BY_ME;
        if (blockedMe) return UserBlockRelationship.BLOCKED_ME;
        return UserBlockRelationship.NONE;
    }

    @Override
    public void assertContentVisible(UUID viewerId, UUID ownerId) {
        if (!viewerId.equals(ownerId) && isBlockedEitherDirection(viewerId, ownerId)) {
            throw new NotFoundException("Resource not found");
        }
    }

    @Override
    public void assertInteractionAllowed(UUID actorId, UUID counterpartId) {
        if (isBlockedEitherDirection(actorId, counterpartId)) {
            throw new ForbiddenException("This interaction is not allowed");
        }
    }
}
