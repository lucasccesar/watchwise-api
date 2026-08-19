package com.watchwise.watchwise_api.userlist.dto;

import jakarta.validation.constraints.NotBlank;

public record UserListCreationDTO(
        @NotBlank String name,
        String description,
        Boolean isPublic
) {
}