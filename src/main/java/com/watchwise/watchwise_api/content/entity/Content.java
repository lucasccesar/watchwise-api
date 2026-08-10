package com.watchwise.watchwise_api.content.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
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

    @Column(name = "created_at", nullable = false)
    @Setter
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter
    private LocalDateTime updatedAt;
}