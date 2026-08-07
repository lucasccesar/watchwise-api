package com.watchwise.watchwise_api.user.dto;

import java.util.UUID;

public record UserPreviewDTO(
        UUID id,
        String username,
        String profilePicture,
        Boolean isProfilePublic
) {
}
