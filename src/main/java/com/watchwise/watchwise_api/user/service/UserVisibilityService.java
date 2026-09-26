package com.watchwise.watchwise_api.user.service;

import java.util.UUID;

public interface UserVisibilityService {

    void assertCanView(UUID viewerId, UUID targetUserId);
}
