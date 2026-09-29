package com.watchwise.watchwise_api.dailygame.entity;

import com.watchwise.watchwise_api.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "user_daily_game_results")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserDailyGameResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "daily_challenge_id", nullable = false)
    private DailyChallenge dailyChallenge;

    @Column(name = "attempts_used", nullable = false)
    @Setter
    @Builder.Default
    private Integer attemptsUsed = 0;

    @Column(name = "score", nullable = false)
    @Setter
    @Builder.Default
    private Integer score = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attempt_details", columnDefinition = "jsonb")
    @Setter
    private Map<String, Object> attemptDetails;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Setter
    @Builder.Default
    private DailyGameResultStatus status = DailyGameResultStatus.IN_PROGRESS;

    @Column(name = "completed_at")
    @Setter
    private LocalDateTime completedAt;

    @Column(name = "share_on_completion", nullable = false)
    @Builder.Default
    private boolean shareOnCompletion = false;

    @Column(name = "shared_at")
    private LocalDateTime sharedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter
    private LocalDateTime updatedAt;

    public void setShareOnCompletion(boolean shareOnCompletion) {
        if (sharedAt == null) {
            this.shareOnCompletion = shareOnCompletion;
        }
    }

    public void markSharedAt(LocalDateTime timestamp) {
        if (sharedAt == null) {
            this.sharedAt = timestamp;
        }
    }
}
