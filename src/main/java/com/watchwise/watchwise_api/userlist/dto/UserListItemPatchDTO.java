package com.watchwise.watchwise_api.userlist.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record UserListItemPatchDTO(
        @Min(1) Integer position,
        @Size(max = 400) String description,
        @Size(max = 2048) @URL String customPosterUrl
) {
    public UserListItemPatchDTO(Integer position, String description) {
        this(position, description, null);
    }
}
