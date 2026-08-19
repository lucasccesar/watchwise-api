package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.util.UUID;

public record UserListPreviewDTO(
        UUID id,
        UserPreviewDTO user,
        String name,
        Boolean isPublic
) {
}