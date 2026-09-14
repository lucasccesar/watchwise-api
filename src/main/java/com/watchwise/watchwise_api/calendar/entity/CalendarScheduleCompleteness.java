package com.watchwise.watchwise_api.calendar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "calendar_schedule_completeness")
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CalendarScheduleCompleteness {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "group_type", length = 10, nullable = false)
    private GroupType groupType;

    @Column(name = "series_tmdb_id", length = 20, nullable = false)
    private String seriesTmdbId;

    @Column(name = "season_number")
    private Integer seasonNumber;

    @Column(name = "region", length = 2, nullable = false)
    private String region;

    @Column(name = "language", length = 10, nullable = false)
    private String language;

    @Column(name = "expected_episode_count", nullable = false)
    private Integer expectedEpisodeCount;

    @Column(name = "complete", nullable = false)
    private Boolean complete;

    @Column(name = "last_checked_at", nullable = false)
    private LocalDateTime lastCheckedAt;

    @Column(name = "last_discovered_at")
    private LocalDateTime lastDiscoveredAt;

    public enum GroupType {
        SEASON,
        SERIES
    }
}
