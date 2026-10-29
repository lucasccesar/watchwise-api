package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import com.watchwise.watchwise_api.common.validation.Utf8ByteLength;

public record LoginUserDTO(
        @NotEmpty @Size(min = 1, max = 60) String identifier,
        @NotEmpty @Utf8ByteLength(max = 72, message = "Password must not exceed 72 UTF-8 bytes") String password,
        boolean rememberMe
) {

    public LoginUserDTO(String identifier, String password) {
        this(identifier, password, false);
    }
}
