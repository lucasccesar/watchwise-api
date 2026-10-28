package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import com.watchwise.watchwise_api.common.validation.Utf8ByteLength;
import org.hibernate.validator.constraints.URL;

public record PostUserDTO(
        @Size(max=60, min = 3) @NotEmpty @Pattern(regexp = "^[^@]+$", message = "Username must not contain @") String username,
        @Size(max=120) @NotBlank String name,
        @Size(max=60) @NotEmpty @Email String email,
        @Size(min=8) @Utf8ByteLength(max=72, message = "Password must not exceed 72 UTF-8 bytes")
        @NotEmpty
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit"
        )
        String password,
        @Size(max=280) String description,
        @Size(max=2048) @URL String profilePicture,
        Boolean isProfilePublic,
        @Size(max=2048) @URL String banner
) {
    public PostUserDTO(String username, String email, String password, String description,
            String profilePicture, Boolean isProfilePublic) {
        this(username, username, email, password, description, profilePicture, isProfilePublic, null);
    }

    public PostUserDTO(String username, String name, String email, String password, String description,
            String profilePicture, Boolean isProfilePublic) {
        this(username, name, email, password, description, profilePicture, isProfilePublic, null);
    }
}
