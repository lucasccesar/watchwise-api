package com.watchwise.watchwise_api.user.dto;

import java.util.UUID;

public record SessionIdentityDTO(
        UUID id,
        String username,
        String email,
        String name,
        String description,
        String profilePicture,
        String banner,
        Boolean isProfilePublic,
        String preferredLanguage,
        String preferredRegion
) {
}
