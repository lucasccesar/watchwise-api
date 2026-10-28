package com.watchwise.watchwise_api.user.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Setter
    @Builder.Default
    private UserRole role = UserRole.USER;

    @Column(length = 60, nullable = false)
    @Setter
    private String username;

    @Column(length = 120, nullable = false)
    @Setter
    private String name;

    @Column(length = 60, nullable = false, unique = true)
    @Setter
    private String email;

    @Column(name = "pending_email", length = 60)
    @Setter
    private String pendingEmail;

    @Column(nullable = false)
    @Setter
    private String password;

    @Column(length = 280)
    @Setter
    private String description;

    @Column(name = "profile_picture", nullable = false, length = 2048)
    @Setter
    @Builder.Default
    private String profilePicture = "https://default-image.png";

    @Column(length = 2048)
    @Setter
    private String banner;

    @Column(name = "is_profile_public", nullable = false)
    @Setter
    @Builder.Default
    private Boolean isProfilePublic = true;

    @Column(name = "is_email_verified", nullable = false)
    @Setter
    @Builder.Default
    private Boolean isEmailVerified = false;

    @Column(name = "email_confirmation_token_hash", length = 64)
    @Setter
    private String emailConfirmationTokenHash;

    @Column(name = "email_confirmation_expires_at")
    @Setter
    private LocalDateTime emailConfirmationExpiresAt;

    @Column(name = "password_reset_token_hash", length = 64)
    @Setter
    private String passwordResetTokenHash;

    @Column(name = "password_reset_expires_at")
    @Setter
    private LocalDateTime passwordResetExpiresAt;

    @Column(name = "created_at", nullable = false)
    @Setter
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter
    private LocalDateTime updatedAt;

    @Column(name = "sessions_invalidated_at")
    @Setter
    private LocalDateTime sessionsInvalidatedAt;

    @Column(name = "auth_version", nullable = false)
    @Setter
    @Builder.Default
    private long authVersion = 0L;

    @Column(name = "preferred_language", nullable = false, length = 10)
    @Setter
    @Builder.Default
    private String preferredLanguage = "en-US";

    @Column(name = "preferred_region", nullable = false, length = 2)
    @Setter
    @Builder.Default
    private String preferredRegion = "US";
}
