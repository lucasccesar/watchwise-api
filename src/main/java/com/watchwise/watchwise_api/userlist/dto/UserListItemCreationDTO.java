package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UserListItemCreationDTO(
        @Valid ContentRefCreationDTO content,
        UUID childListId,
        @Min(1) Integer position,
        @Size(max = 400) String description
) {
}