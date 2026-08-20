package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import jakarta.validation.constraints.NotBlank;

public record UserListCreationDTO(
        @NotBlank String name,
        String description,
        UserListVisibility visibility
) {
}