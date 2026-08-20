package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserListBulkCreationDTO(
        @NotBlank String name,
        String description,
        UserListVisibility visibility,
        @NotEmpty @Size(max = 100) List<@Valid ContentRefCreationDTO> items
) {
}
