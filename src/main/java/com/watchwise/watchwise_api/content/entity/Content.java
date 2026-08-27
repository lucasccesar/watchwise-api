package com.watchwise.watchwise_api.content.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "contents")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Content {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @Column(name = "tmdb_id", length = 20)
    @Setter
    private String tmdbId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @Setter
    private ContentType type;

    @Column(name = "series_tmdb_id", length = 20)
    @Setter
    private String seriesTmdbId;

    @Column(name = "season_number")
    @Setter
    private Integer seasonNumber;

    @Column(name = "episode_number")
    @Setter
    private Integer episodeNumber;

    @Column(name = "is_season_finale")
    @Setter
    private Boolean isSeasonFinale;

    @Column(name = "is_series_finale")
    @Setter
    private Boolean isSeriesFinale;

    @Column(name = "runtime_minutes")
    @Setter
    private Integer runtimeMinutes;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "genres")
    @Setter
    private List<String> genres;

    @Column(name = "created_at", nullable = false)
    @Setter
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter
    private LocalDateTime updatedAt;
}