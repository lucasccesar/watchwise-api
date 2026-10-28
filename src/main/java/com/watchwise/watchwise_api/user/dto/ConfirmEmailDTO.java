package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmEmailDTO(@NotBlank @Size(max = 128) String token) {
}
