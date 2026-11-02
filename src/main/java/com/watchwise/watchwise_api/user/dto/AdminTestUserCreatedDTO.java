package com.watchwise.watchwise_api.user.dto;

import java.util.UUID;

public record AdminTestUserCreatedDTO(
        UUID id,
        String username,
        String email,
        boolean created
) {
}
