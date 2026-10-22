package com.watchwise.watchwise_api.common.transaction;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UserContentMutationLock {

    private static final String LOCK_PREFIX = "content-state:";

    private final AdvisoryLock advisoryLock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID ownerId) {
        advisoryLock.lock(LOCK_PREFIX + ownerId);
    }
}
