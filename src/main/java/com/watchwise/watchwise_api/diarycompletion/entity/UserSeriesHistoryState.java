package com.watchwise.watchwise_api.diarycompletion.entity;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_series_history_states")
@Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor
public class UserSeriesHistoryState {
    @EmbeddedId private Key id;
    @Column(name = "history_revision", nullable = false) private Long historyRevision;
    @Column(name = "computed_revision", nullable = false) private Long computedRevision;
    @Column(name = "schedule_version", nullable = false) private Long scheduleVersion;
    @Column(name = "valid_until") private Instant validUntil;
    @Version private Long version;

    @Embeddable
    @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "series_tmdb_id", nullable = false) private String seriesTmdbId;
    }
}
