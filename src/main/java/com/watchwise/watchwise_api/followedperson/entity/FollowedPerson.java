package com.watchwise.watchwise_api.followedperson.entity;

import com.watchwise.watchwise_api.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "followed_people")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class FollowedPerson {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "person_tmdb_id", length = 20, nullable = false)
    @Setter
    private String personTmdbId;

    @Column(name = "created_at", nullable = false)
    @Setter
    private LocalDateTime createdAt;
}