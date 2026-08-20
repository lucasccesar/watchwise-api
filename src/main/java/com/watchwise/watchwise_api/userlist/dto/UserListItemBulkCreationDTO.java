package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserListItemBulkCreationDTO(
        @NotEmpty @Size(max = 100) List<@Valid ContentRefCreationDTO> items
) {
}
