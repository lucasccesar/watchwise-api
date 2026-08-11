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

    @Column(length = 60, nullable = false)
    @Setter
    private String username;

    @Column(length = 60, nullable = false, unique = true)
    @Setter
    private String email;

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

    @Column(name = "is_profile_public", nullable = false)
    @Setter
    @Builder.Default
    private Boolean isProfilePublic = true;

    @Column(name = "is_email_verified", nullable = false)
    @Setter
    @Builder.Default
    private Boolean isEmailVerified = true;

    @Column(name = "created_at", nullable = false)
    @Setter
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter
    private LocalDateTime updatedAt;
}
