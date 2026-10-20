package com.watchwise.watchwise_api.userblock.service;

import com.watchwise.watchwise_api.userblock.entity.UserBlockRelationship;

import java.util.UUID;

public interface UserBlockAccessService {
    boolean isBlockedEitherDirection(UUID firstUserId, UUID secondUserId);
    UserBlockRelationship relationship(UUID viewerId, UUID targetUserId);
    void assertContentVisible(UUID viewerId, UUID ownerId);
    void assertInteractionAllowed(UUID actorId, UUID counterpartId);
}
