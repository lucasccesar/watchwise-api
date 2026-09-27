package com.watchwise.watchwise_api.dailygame.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.util.UUID;

@Entity
@Table(name = "daily_challenge_hints")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DailyChallengeHint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "daily_challenge_id", nullable = false)
    private DailyChallenge dailyChallenge;

    @Column(name = "position", nullable = false)
    private Integer position;

    @Column(name = "hint_type", length = 50, nullable = false)
    private String hintType;

    @Column(name = "hint_value", columnDefinition = "text", nullable = false)
    private String hintValue;
}
