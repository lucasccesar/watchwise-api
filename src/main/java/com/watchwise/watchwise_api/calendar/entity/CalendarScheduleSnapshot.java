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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "calendar_schedule_snapshots")
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CalendarScheduleSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 10, nullable = false)
    private EventType eventType;

    @Column(name = "tmdb_id", length = 20)
    private String tmdbId;

    @Column(name = "series_tmdb_id", length = 20)
    private String seriesTmdbId;

    @Column(name = "season_number")
    private Integer seasonNumber;

    @Column(name = "episode_number")
    private Integer episodeNumber;

    @Column(name = "region", length = 2, nullable = false)
    private String region;

    @Column(name = "language", length = 10, nullable = false)
    private String language;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "title", length = 500, nullable = false)
    private String title;

    @Column(name = "series_title", length = 500)
    private String seriesTitle;

    @Column(name = "poster_path", length = 500)
    private String posterPath;

    @Column(name = "still_path", length = 500)
    private String stillPath;

    @Column(name = "last_checked_at", nullable = false)
    private LocalDateTime lastCheckedAt;

    @Column(name = "next_check_at", nullable = false)
    private LocalDateTime nextCheckAt;

    @Column(name = "present_in_last_tmdb_snapshot", nullable = false)
    private Boolean presentInLastTmdbSnapshot;

    public enum EventType {
        MOVIE,
        EPISODE
    }
}
