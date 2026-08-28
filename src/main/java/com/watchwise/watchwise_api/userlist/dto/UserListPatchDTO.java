package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import jakarta.validation.constraints.Positive;

public record UserListPatchDTO(
        String name,
        String description,
        UserListVisibility visibility,
        @Positive Integer rank
) {
    public UserListPatchDTO(String name, String description, UserListVisibility visibility) {
        this(name, description, visibility, null);
    }
}