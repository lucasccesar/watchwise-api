package com.watchwise.watchwise_api.user.service;

import com.watchwise.watchwise_api.user.dto.AdminTestUserCreatedDTO;
import com.watchwise.watchwise_api.user.dto.AdminTestUserCreationDTO;

import java.util.UUID;

public interface TestUserProvisioningService {
    AdminTestUserCreatedDTO createTestUser(UUID adminId, AdminTestUserCreationDTO request);
}
