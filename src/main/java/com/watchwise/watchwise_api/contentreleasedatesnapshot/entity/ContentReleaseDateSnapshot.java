package com.watchwise.watchwise_api.contentreleasedatesnapshot.entity;

import com.watchwise.watchwise_api.content.entity.ContentType;
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
@Table(name = "content_release_date_snapshots")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ContentReleaseDateSnapshot {

    public enum Status {
        FOUND,
        NOT_FOUND,
        UNAVAILABLE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tmdb_id", nullable = false, length = 20)
    private String tmdbId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private ContentType type;

    @Column(length = 2)
    private String region;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 11)
    @Setter
    private Status status;

    @Column(name = "last_checked_at", nullable = false)
    @Setter
    private LocalDateTime lastCheckedAt;

    @Column(name = "next_check_at", nullable = false)
    @Setter
    private LocalDateTime nextCheckAt;

    public void update(LocalDate releaseDate, Status status, LocalDateTime checkedAt, LocalDateTime nextCheckAt) {
        this.releaseDate = releaseDate;
        this.status = status;
        this.lastCheckedAt = checkedAt;
        this.nextCheckAt = nextCheckAt;
    }
}
