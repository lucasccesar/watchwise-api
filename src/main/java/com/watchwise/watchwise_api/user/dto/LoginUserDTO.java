package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.constraints.NotEmpty;

public record LoginUserDTO(
        @NotEmpty String identifier,
        @NotEmpty String password
) {
}
