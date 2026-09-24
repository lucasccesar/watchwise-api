package com.watchwise.watchwise_api.seriesprogress.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "series_progress_metadata")
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SeriesProgressMetadata {

    @Id
    @Column(name = "series_tmdb_id", length = 20, nullable = false)
    private String seriesTmdbId;

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

    @Column(name = "runtime_verified_at")
    private LocalDateTime runtimeVerifiedAt;
}
