package com.watchwise.watchwise_api.userblock.service;

import com.watchwise.watchwise_api.userblock.entity.UserBlockRelationship;

import java.util.UUID;
import java.util.Collection;

public interface UserBlockAccessService {
    boolean isBlockedEitherDirection(UUID firstUserId, UUID secondUserId);
    UserBlockRelationship relationship(UUID viewerId, UUID targetUserId);
    void assertContentVisible(UUID viewerId, UUID ownerId);
    void lockAndAssertContentVisible(UUID viewerId, UUID ownerId);
    void assertInteractionAllowed(UUID actorId, UUID counterpartId);
    void lockAndAssertInteractionAllowed(UUID actorId, UUID counterpartId);
    void lockAndAssertInteractionAllowed(UUID actorId, Collection<UUID> counterpartIds);
}
