package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;

import java.util.UUID;

public record UserListPreviewDTO(
        UUID id,
        UserPreviewDTO user,
        String name,
        UserListVisibility visibility
) {
}