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
        String currentPassword,
        @Size(max=2048) @URL String banner,
        @Pattern(regexp = "^[a-z]{2}-[A-Z]{2}$", message = "preferredLanguage must be in the format en-US")
        String preferredLanguage,
        @Pattern(regexp = "^[A-Z]{2}$", message = "preferredRegion must be an ISO 3166-1 alpha-2 code, e.g. US")
        String preferredRegion
) {
    public PatchUserDTO(String username, String email, String password, String description,
            String profilePicture, Boolean isProfilePublic, String currentPassword) {
        this(username, email, password, description, profilePicture, isProfilePublic, currentPassword, null, null, null);
    }

    public PatchUserDTO(String username, String email, String password, String description,
            String profilePicture, Boolean isProfilePublic, String currentPassword, String banner) {
        this(username, email, password, description, profilePicture, isProfilePublic, currentPassword, banner, null, null);
    }
}