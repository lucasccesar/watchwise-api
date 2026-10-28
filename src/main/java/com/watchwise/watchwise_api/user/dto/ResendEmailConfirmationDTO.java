package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResendEmailConfirmationDTO(@NotBlank @Email @Size(max = 60) String email) {
}
