package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;

public record UserListPatchDTO(
        String name,
        String description,
        UserListVisibility visibility
) {
}