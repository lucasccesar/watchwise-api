package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import com.watchwise.watchwise_api.common.validation.Utf8ByteLength;
import org.hibernate.validator.constraints.URL;

public record PatchUserDTO(
        @Size(max=60, min = 3) @Pattern(regexp = "^[^@]+$", message = "Username must not contain @") String username,
        @Size(max=120) String name,
        @Size(max=60) @Email @Pattern(regexp = ".*\\S.*", message = "Email must not be blank") String email,
        @Size(min=8) @Utf8ByteLength(max=72, message = "Password must not exceed 72 UTF-8 bytes")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit"
        )
        String password,
        @Size(max=280) String description,
        @Size(max=2048) @URL String profilePicture,
        Boolean isProfilePublic,
        @Utf8ByteLength(max = 72, message = "Current password must not exceed 72 UTF-8 bytes") String currentPassword,
        @Size(max=2048) @URL String banner,
        @Pattern(regexp = "^[a-z]{2}-[A-Z]{2}$", message = "preferredLanguage must be in the format en-US")
        String preferredLanguage,
        @Pattern(regexp = "^[A-Z]{2}$", message = "preferredRegion must be an ISO 3166-1 alpha-2 code, e.g. US")
        String preferredRegion
) {
    public PatchUserDTO(String username, String email, String password, String description,
            String profilePicture, Boolean isProfilePublic, String currentPassword, String banner,
            String preferredLanguage, String preferredRegion) {
        this(username, null, email, password, description, profilePicture, isProfilePublic, currentPassword,
                banner, preferredLanguage, preferredRegion);
    }

    public PatchUserDTO(String username, String email, String password, String description,
            String profilePicture, Boolean isProfilePublic, String currentPassword) {
        this(username, null, email, password, description, profilePicture, isProfilePublic, currentPassword, null, null, null);
    }

    public PatchUserDTO(String username, String email, String password, String description,
            String profilePicture, Boolean isProfilePublic, String currentPassword, String banner) {
        this(username, null, email, password, description, profilePicture, isProfilePublic, currentPassword, banner, null, null);
    }
}
