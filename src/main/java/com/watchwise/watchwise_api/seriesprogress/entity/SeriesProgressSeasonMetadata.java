package com.watchwise.watchwise_api.seriesprogress.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "series_progress_season_metadata",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_series_progress_season_metadata_identity",
                columnNames = {"series_tmdb_id", "season_number"}))
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SeriesProgressSeasonMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "series_tmdb_id", length = 20, nullable = false)
    private String seriesTmdbId;

    @Column(name = "season_number", nullable = false)
    private Integer seasonNumber;

    @Column(name = "regular_released_episode_count", nullable = false)
    private Integer regularReleasedEpisodeCount;

    @Column(name = "total_known_runtime", nullable = false)
    private Integer totalKnownRuntime;

    @Column(name = "known_runtime_episode_count", nullable = false)
    private Integer knownRuntimeEpisodeCount;

    @Column(name = "last_released_episode_date")
    private LocalDate lastReleasedEpisodeDate;

    @Column(name = "refreshed_at", nullable = false)
    private LocalDateTime refreshedAt;
}
