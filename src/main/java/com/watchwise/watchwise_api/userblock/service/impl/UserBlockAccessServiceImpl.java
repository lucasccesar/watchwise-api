package com.watchwise.watchwise_api.userblock.service.impl;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserBlockAccessServiceImpl implements UserBlockAccessService {

    private final UserBlockRepository userBlockRepository;

    @Override
    public boolean isBlockedEitherDirection(UUID firstUserId, UUID secondUserId) {
        return userBlockRepository.existsEitherDirection(firstUserId, secondUserId);
    }

    @Override
    public void assertInteractionAllowed(UUID actorId, UUID counterpartId) {
        if (isBlockedEitherDirection(actorId, counterpartId)) {
            throw new ForbiddenException("This interaction is not allowed");
        }
    }
}
