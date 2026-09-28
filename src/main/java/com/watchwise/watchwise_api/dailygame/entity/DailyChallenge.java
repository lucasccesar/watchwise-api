package com.watchwise.watchwise_api.dailygame.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "daily_challenges")
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DailyChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @Column(name = "challenge_date", nullable = false)
    private LocalDate challengeDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_type", length = 40, nullable = false)
    private DailyGameType gameType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", length = 10, nullable = false)
    private DailyGameTargetKind targetKind;

    @Column(name = "target_tmdb_id", length = 20)
    private String targetTmdbId;

    @Column(name = "series_tmdb_id", length = 20)
    private String seriesTmdbId;

    @Column(name = "season_number")
    private Integer seasonNumber;

    @Column(name = "episode_number")
    private Integer episodeNumber;

    @Column(name = "answer_key", length = 256, nullable = false)
    private String answerKey;

    @Column(name = "source_tmdb_id", length = 20)
    private String sourceTmdbId;

    @Column(name = "image_path", length = 500, nullable = false)
    private String imagePath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer_snapshot", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> answerSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "display_snapshot", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> displaySnapshot;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter
    private LocalDateTime updatedAt;
}
