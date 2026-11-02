package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminBootstrapRequestDTO(
        @NotBlank
        @Size(min = 3, max = 60)
        @Pattern(regexp = "^[^@]+$", message = "Username must not contain @")
        String username
) {
}
