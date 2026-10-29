package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.constraints.NotEmpty;
import com.watchwise.watchwise_api.common.validation.Utf8ByteLength;

public record OAuthLoginDTO(
        @NotEmpty @Utf8ByteLength(max = 16_384, message = "OAuth token must not exceed 16 KiB") String token,
        boolean rememberMe
) {

    public OAuthLoginDTO(String token) {
        this(token, false);
    }
}
