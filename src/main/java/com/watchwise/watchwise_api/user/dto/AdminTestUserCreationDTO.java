package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.watchwise.watchwise_api.common.validation.Utf8ByteLength;

public record AdminTestUserCreationDTO(
        @NotBlank
        @Size(min = 3, max = 60)
        @Pattern(regexp = "^[^@]+$", message = "Username must not contain @")
        String username,
        @Size(max = 120) String name,
        @NotBlank
        @Size(min = 16)
        @Utf8ByteLength(max = 72, message = "Password must not exceed 72 UTF-8 bytes")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit"
        )
        String password
) {
}
