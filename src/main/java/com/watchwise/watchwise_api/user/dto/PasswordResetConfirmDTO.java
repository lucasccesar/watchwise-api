package com.watchwise.watchwise_api.user.dto;

import com.watchwise.watchwise_api.common.validation.Utf8ByteLength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmDTO(
        @NotBlank @Size(max = 128) String token,
        @NotBlank
        @Size(min = 8, max = 255)
        @Utf8ByteLength(max = 72, message = "Password must not exceed 72 UTF-8 bytes")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit")
        String password) {
}
