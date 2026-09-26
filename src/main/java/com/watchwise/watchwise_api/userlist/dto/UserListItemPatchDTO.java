package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.common.validation.TmdbPosterUrl;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UserListItemPatchDTO(
        @Min(1) Integer position,
        @Size(max = 400) String description,
        @Size(max = 2048) @TmdbPosterUrl String customPosterUrl
) {
    public UserListItemPatchDTO(Integer position, String description) {
        this(position, description, null);
    }
}
