package com.watchwise.watchwise_api.settings.dto;

import java.util.UUID;

public record SettingsUserPreviewDTO(UUID id, String username, String name, String profilePicture) {}
