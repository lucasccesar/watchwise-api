package com.watchwise.watchwise_api.user.dto;

import java.util.UUID;

public record UserPreviewDTO(
        UUID id,
        String username,
        String name,
        String profilePicture,
        Boolean isProfilePublic
) {
    public UserPreviewDTO(UUID id, String username, String profilePicture, Boolean isProfilePublic) {
        this(id, username, username, profilePicture, isProfilePublic);
    }
}
