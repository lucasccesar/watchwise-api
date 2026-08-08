package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.constraints.NotEmpty;

public record DeleteAccountDTO(
        @NotEmpty String password
) {
}
