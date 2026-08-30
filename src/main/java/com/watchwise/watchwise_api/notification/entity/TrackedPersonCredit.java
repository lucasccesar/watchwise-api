package com.watchwise.watchwise_api.notification.entity;

import com.watchwise.watchwise_api.content.entity.ContentType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "tracked_person_credits")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TrackedPersonCredit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tracked_person_state_id", nullable = false)
    private TrackedPersonState trackedPersonState;

    @Column(name = "credit_tmdb_id", length = 20, nullable = false)
    @Setter
    private String creditTmdbId;

    @Enumerated(EnumType.STRING)
    @Column(name = "credit_type", length = 6, nullable = false)
    @Setter
    private ContentType creditType;
}
