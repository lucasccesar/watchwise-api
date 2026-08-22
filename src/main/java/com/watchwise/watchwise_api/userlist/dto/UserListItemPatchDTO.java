package com.watchwise.watchwise_api.userlist.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UserListItemPatchDTO(
        @Min(1) Integer position,
        @Size(max = 400) String description
) {
}
