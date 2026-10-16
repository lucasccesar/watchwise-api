package com.watchwise.watchwise_api.userblock.dto;

import com.watchwise.watchwise_api.user.dto.PublicUserDTO;

import java.time.LocalDateTime;

public record BlockedUserDTO(PublicUserDTO user, LocalDateTime createdAt) {
}
