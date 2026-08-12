package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record PatchUserDTO(
        @Size(max=60, min = 3) String username,
        @Size(max=60, min=11) @Email String email,
        @Size(max=255, min=8)
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit"
        )
        String password,
        @Size(max=280) String description,
        @Size(max=2048) @URL String profilePicture,
        Boolean isProfilePublic,
        String currentPassword
) {
}