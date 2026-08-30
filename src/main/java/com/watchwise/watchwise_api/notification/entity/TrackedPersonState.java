package com.watchwise.watchwise_api.notification.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tracked_person_states")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TrackedPersonState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @Column(name = "person_tmdb_id", length = 20, nullable = false)
    @Setter
    private String personTmdbId;

    @Column(name = "last_checked_at", nullable = false)
    @Setter
    private LocalDateTime lastCheckedAt;
}
