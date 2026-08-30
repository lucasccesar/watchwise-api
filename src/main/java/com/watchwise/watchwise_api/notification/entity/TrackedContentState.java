package com.watchwise.watchwise_api.notification.entity;

import com.watchwise.watchwise_api.content.entity.Content;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tracked_content_states")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TrackedContentState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    @Column(name = "last_known_release_date")
    @Setter
    private LocalDate lastKnownReleaseDate;

    @Column(name = "last_known_status", length = 30)
    @Setter
    private String lastKnownStatus;

    @Column(name = "next_episode_air_date")
    @Setter
    private LocalDate nextEpisodeAirDate;

    @Column(name = "next_episode_season_number")
    @Setter
    private Integer nextEpisodeSeasonNumber;

    @Column(name = "next_episode_number")
    @Setter
    private Integer nextEpisodeNumber;

    @Column(name = "last_checked_at", nullable = false)
    @Setter
    private LocalDateTime lastCheckedAt;
}
