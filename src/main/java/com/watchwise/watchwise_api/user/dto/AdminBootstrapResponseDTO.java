package com.watchwise.watchwise_api.user.dto;

import com.watchwise.watchwise_api.user.entity.UserRole;

import java.util.UUID;

public record AdminBootstrapResponseDTO(UUID id, String username, UserRole role) {
}
