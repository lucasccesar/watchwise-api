package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserListCreationDTO(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 400) String description,
        UserListVisibility visibility
) {
}