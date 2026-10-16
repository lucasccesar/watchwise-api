package com.watchwise.watchwise_api.settings.dto;

import java.util.UUID;

public record SettingsProfileDTO(UUID id, String username, String name, String description,
        String profilePicture, String banner, Boolean isProfilePublic) {}
