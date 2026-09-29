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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DailyChallengeResponseAssemblerTest {

    private final DailyChallengeResponseAssembler assembler = new DailyChallengeResponseAssembler();

    @Test
    @DisplayName("[images] Should Expose Previous And Terminal Images - When The Episode Game Advances")
    void shouldExposePreviousAndTerminalImagesWhenTheEpisodeGameAdvances() {
        DailyChallenge challenge = DailyChallenge.builder()
                .id(UUID.randomUUID())
                .challengeDate(LocalDate.of(2026, 9, 27))
                .gameType(DailyGameType.EPISODE_BY_FRAME)
                .targetKind(DailyGameTargetKind.EPISODE)
                .seriesTmdbId("1396")
                .seasonNumber(1)
                .episodeNumber(3)
                .answerKey("EPISODE:1396:1:3")
                .imagePath("/third.jpg")
                .answerSnapshot(Map.of("title", "Episode 3"))
                .displaySnapshot(Map.of("imagePaths", List.of("/third.jpg", "/second.jpg", "/first.jpg")))
                .createdAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .build();

        DailyGameAttemptResponseDTO open = assembler.toAttemptResponse(challenge, result(2, DailyGameResultStatus.IN_PROGRESS));
        DailyGameAttemptResponseDTO completed = assembler.toAttemptResponse(
                challenge, result(1, DailyGameResultStatus.COMPLETED));

        assertThat(open.visibleImageUrls()).containsExactly(
                "https://image.tmdb.org/t/p/w300/third.jpg",
                "https://image.tmdb.org/t/p/w300/second.jpg",
                "https://image.tmdb.org/t/p/w300/first.jpg");
        assertThat(open.imageUrls()).isNull();
        assertThat(completed.imageUrls()).containsExactly(
                "https://image.tmdb.org/t/p/w300/third.jpg",
                "https://image.tmdb.org/t/p/w300/second.jpg",
                "https://image.tmdb.org/t/p/w300/first.jpg");
    }

    @Test
    @DisplayName("[images] Should Repeat Only The Last Image - When The Episode Has Fewer Than Six Stills")
    void shouldRepeatOnlyTheLastImageWhenTheEpisodeHasFewerThanSixStills() {
        DailyChallenge challenge = DailyChallenge.builder()
                .id(UUID.randomUUID())
                .challengeDate(LocalDate.of(2026, 9, 27))
                .gameType(DailyGameType.EPISODE_BY_FRAME)
                .targetKind(DailyGameTargetKind.EPISODE)
                .seriesTmdbId("1396")
                .seasonNumber(1)
                .episodeNumber(3)
                .answerKey("EPISODE:1396:1:3")
                .imagePath("/second.jpg")
                .answerSnapshot(Map.of("title", "Episode 3"))
                .displaySnapshot(Map.of("imagePaths", List.of("/second.jpg", "/first.jpg")))
                .createdAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .build();

        DailyGameAttemptResponseDTO response = assembler.toAttemptResponse(
                challenge, result(5, DailyGameResultStatus.IN_PROGRESS));

        assertThat(response.visibleImageUrls()).containsExactly(
                "https://image.tmdb.org/t/p/w300/second.jpg",
                "https://image.tmdb.org/t/p/w300/first.jpg",
                "https://image.tmdb.org/t/p/w300/first.jpg",
                "https://image.tmdb.org/t/p/w300/first.jpg",
                "https://image.tmdb.org/t/p/w300/first.jpg",
                "https://image.tmdb.org/t/p/w300/first.jpg");
    }

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
        return result(0, DailyGameResultStatus.IN_PROGRESS);
    }

    private UserDailyGameResult result(int attemptsUsed, DailyGameResultStatus status) {
        return UserDailyGameResult.builder()
                .id(UUID.randomUUID())
                .attemptsUsed(attemptsUsed)
                .score(0)
                .status(status)
                .createdAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .build();
    }
}
