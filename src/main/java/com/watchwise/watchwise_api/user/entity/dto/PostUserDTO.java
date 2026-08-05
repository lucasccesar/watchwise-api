package com.watchwise.watchwise_api.user.entity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record PostUserDTO(
        @Size(max=60, min = 3) @NotEmpty String username,
        @Size(max=60, min=11) @NotEmpty @Email String email,
        @Size(max=255) @NotEmpty String password,
        @Size(max=280) String description,
        @Size(max=2048) String profile_picture,
        Boolean is_profile_public
) {
}
