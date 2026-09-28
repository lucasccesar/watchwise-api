package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptResponseDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameResultStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DailyChallengeResponseAssemblerTest {

    private final DailyChallengeResponseAssembler assembler = new DailyChallengeResponseAssembler();

    @Test
    @DisplayName("[imageUrl] Should Preserve The Absolute Url - When The Challenge Stores A Complete Tmdb Url")
    void shouldPreserveTheAbsoluteUrlWhenTheChallengeStoresACompleteTmdbUrl() {
        DailyGameAttemptResponseDTO response = assembler.toAttemptResponse(
                challenge("https://image.tmdb.org/t/p/w500/fight-club.jpg"), result());

        assertThat(response.imageUrl()).isEqualTo("https://image.tmdb.org/t/p/w500/fight-club.jpg");
    }

    @Test
    @DisplayName("[imageUrl] Should Build The Poster Url - When The Challenge Stores A Relative Tmdb Path")
    void shouldBuildThePosterUrlWhenTheChallengeStoresARelativeTmdbPath() {
        DailyGameAttemptResponseDTO response = assembler.toAttemptResponse(challenge("/fight-club.jpg"), result());

        assertThat(response.imageUrl()).isEqualTo("https://image.tmdb.org/t/p/w500/fight-club.jpg");
    }

    private DailyChallenge challenge(String imagePath) {
        return DailyChallenge.builder()
                .id(UUID.randomUUID())
                .challengeDate(LocalDate.of(2026, 9, 27))
                .gameType(DailyGameType.MOVIE_BY_POSTER)
                .targetKind(DailyGameTargetKind.MOVIE)
                .targetTmdbId("550")
                .answerKey("MOVIE:550")
                .imagePath(imagePath)
                .createdAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .build();
    }

    private UserDailyGameResult result() {
        return UserDailyGameResult.builder()
                .id(UUID.randomUUID())
                .attemptsUsed(0)
                .score(0)
                .status(DailyGameResultStatus.IN_PROGRESS)
                .createdAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .build();
    }
}
