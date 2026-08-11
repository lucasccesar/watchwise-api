package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.constraints.NotEmpty;

public record OAuthLoginDTO(
        @NotEmpty String token
) {
}