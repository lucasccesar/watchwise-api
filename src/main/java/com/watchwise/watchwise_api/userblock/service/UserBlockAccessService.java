package com.watchwise.watchwise_api.userblock.service;

import java.util.UUID;

public interface UserBlockAccessService {
    boolean isBlockedEitherDirection(UUID firstUserId, UUID secondUserId);
    void assertInteractionAllowed(UUID actorId, UUID counterpartId);
}
