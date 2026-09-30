package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameCandidateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameComparisonCellDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameComparisonStatus;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameGuessFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameInfoFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyEntryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameActorGuessDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameStateDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameResultStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import com.watchwise.watchwise_api.dailygame.service.DailyGameAttemptDetailsCodec;
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
    @DisplayName("[answer] Should Expose Frozen Series Metadata - When The Episode Result Is Terminal")
    void shouldExposeFrozenSeriesMetadataWhenTheEpisodeResultIsTerminal() {
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
                .answerSnapshot(Map.of(
                        "seriesTmdbId", "1396",
                        "seriesName", "Breaking Bad",
                        "seriesPosterPath", "/poster.jpg",
                        "seriesYear", 2008,
                        "episodeName", "Pilot",
                        "seasonNumber", 1,
                        "episodeNumber", 3,
                        "title", "Pilot",
                        "imageUrl", "/third.jpg"))
                .displaySnapshot(Map.of("imagePaths", List.of("/third.jpg", "/second.jpg", "/first.jpg")))
                .createdAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .build();

        DailyGameAttemptResponseDTO open = assembler.toAttemptResponse(
                challenge, result(1, DailyGameResultStatus.IN_PROGRESS));
        DailyGameAttemptResponseDTO terminal = assembler.toAttemptResponse(
                challenge, result(1, DailyGameResultStatus.COMPLETED));

        assertThat(open.answer()).isNull();
        assertThat(terminal.answer()).satisfies(answer -> {
            assertThat(answer.seriesName()).isEqualTo("Breaking Bad");
            assertThat(answer.seriesPosterUrl()).isEqualTo("https://image.tmdb.org/t/p/w500/poster.jpg");
            assertThat(answer.seriesYear()).isEqualTo(2008);
            assertThat(answer.title()).isEqualTo("Pilot");
        });
    }

    @Test
    @DisplayName("[images] Should Preserve Legacy Image Fallback - When A Stored Episode Has Fewer Than Six Stills")
    void shouldPreserveLegacyImageFallbackWhenAStoredEpisodeHasFewerThanSixStills() {
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

    @Test
    @DisplayName("[attempts] Should Expose Current Day Attempts Without Revealing The Answer - When The Result Is Open")
    void shouldExposeCurrentDayAttemptsWithoutRevealingTheAnswerWhenTheResultIsOpen() {
        DailyChallenge challenge = challenge("/fight-club.jpg");
        UserDailyGameResult result = result();
        DailyGameAttemptDTO attempt = new DailyGameAttemptDTO(
                1,
                new DailyGameCandidateDTO(
                        DailyGameTargetKind.MOVIE, "680", null, null, null, null,
                        "The Secret Guess", "/guess.jpg", LocalDate.of(2000, 1, 1)),
                new DailyGameGuessFeedbackDTO(false, false, false, false), infoFeedback(), null);
        result.setAttemptDetails(new DailyGameAttemptDetailsCodec().append(null, attempt));

        DailyGameAttemptResponseDTO response = assembler.toAttemptResponse(
                challenge, result, List.of(), null, true);

        assertThat(response.attempts()).containsExactly(attempt);
        assertThat(response.attempts().getFirst().infoFeedback()).isEqualTo(infoFeedback());
        assertThat(response.answer()).isNull();
    }

    @Test
    @DisplayName("[state] Should Expose Typed Info Feedback - When Current Day Attempts Are Requested")
    void shouldExposeTypedInfoFeedbackWhenCurrentDayAttemptsAreRequested() {
        DailyChallenge challenge = challenge("/fight-club.jpg");
        UserDailyGameResult result = result();
        result.setAttemptDetails(new DailyGameAttemptDetailsCodec().append(null,
                new DailyGameAttemptDTO(1, null, null, infoFeedback(), null)));

        DailyGameStateDTO state = assembler.toState(challenge, result, List.of(), true);

        assertThat(state.attempts()).hasSize(1);
        assertThat(state.attempts().getFirst().infoFeedback()).isEqualTo(infoFeedback());
        assertThat(state.answer()).isNull();
    }

    @Test
    @DisplayName("[state] Should Reproject Series Filmography Without Remote Data - When Major Roles Change")
    void shouldReprojectSeriesFilmographyWithoutRemoteDataWhenMajorRolesChange() {
        DailyChallenge challenge = DailyChallenge.builder()
                .id(UUID.randomUUID())
                .challengeDate(LocalDate.of(2026, 9, 27))
                .gameType(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY)
                .targetKind(DailyGameTargetKind.PERSON)
                .targetTmdbId("1")
                .answerKey("PERSON:1")
                .imagePath("/actor.jpg")
                .answerSnapshot(Map.of("title", "Secret Actor", "filmography", List.of(
                        Map.of("workId", "10", "title", "Major", "mediaType", "tv", "year", 2000,
                                "genres", List.of("18"), "episodeCount", 2, "totalEpisodes", 6,
                                "period", "2000-01-01"),
                        Map.of("workId", "20", "title", "Guest", "mediaType", "tv", "year", 2001,
                                "genres", List.of("18"), "episodeCount", 2, "totalEpisodes", 7,
                                "period", "2001-01-01"))))
                .displaySnapshot(Map.of("imageUrl", "/actor.jpg"))
                .createdAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .build();
        UserDailyGameResult result = result();
        DailyGameFilmographyFeedbackDTO feedback = new DailyGameFilmographyFeedbackDTO(
                new DailyGameActorGuessDTO("2", "Guessed Actor"),
                List.of("SERIES:10"), List.of("SERIES:10", "SERIES:20"), List.of());
        result.setAttemptDetails(new DailyGameAttemptDetailsCodec().append(null,
                new DailyGameAttemptDTO(1, null, null, null, feedback)));

        DailyGameStateDTO major = assembler.toState(challenge, result, List.of(), true, true);
        DailyGameStateDTO all = assembler.toState(challenge, result, List.of(), true, false);

        assertThat(major.filmography().guessedActors()).containsExactly(new DailyGameActorGuessDTO("2", "Guessed Actor"));
        assertThat(major.filmography().entries()).extracting(entry -> entry.workId() + ":" + entry.title())
                .containsExactly("10:Major");
        assertThat(all.filmography().entries()).extracting(entry -> entry.workId() + ":" + entry.title())
                .containsExactly("10:Major", "20:Guest");
    }

    private DailyGameInfoFeedbackDTO infoFeedback() {
        DailyGameComparisonCellDTO match = new DailyGameComparisonCellDTO(
                DailyGameComparisonStatus.MATCH, null, "value", List.of(), null);
        return new DailyGameInfoFeedbackDTO(match, match, match, match, match, match, match, match);
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
