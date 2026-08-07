package com.watchwise.watchwise_api.user.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record PublicUserDTO(
        UUID id,
        String username,
        String description,
        String profilePicture,
        Boolean isProfilePublic,
        LocalDateTime createdAt
) {
}
