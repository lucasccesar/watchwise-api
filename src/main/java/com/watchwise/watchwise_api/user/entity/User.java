package com.watchwise.watchwise_api.user.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 60, nullable = false)
    private String username;

    @Column(length = 60, nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(length = 280)
    private String description;

    @Column(name = "profile_picture", nullable = false, length = 2048)
    @Builder.Default
    private String profilePicture = "https://default-image.png";

    @Column(name = "is_profile_public", nullable = false)
    @Builder.Default
    private Boolean isProfilePublic = true;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
