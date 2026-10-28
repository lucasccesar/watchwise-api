package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.constraints.NotEmpty;
import com.watchwise.watchwise_api.common.validation.Utf8ByteLength;

public record DeleteAccountDTO(
        @NotEmpty @Utf8ByteLength(max = 72, message = "Password must not exceed 72 UTF-8 bytes") String password
) {
}
