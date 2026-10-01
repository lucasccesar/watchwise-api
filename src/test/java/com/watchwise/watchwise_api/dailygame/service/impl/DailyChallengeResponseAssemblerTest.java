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
import com.watchwise.watchwise_api.dailygame.dto.DailyGameHistoryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameStateDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallengeHint;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameResultStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import com.watchwise.watchwise_api.dailygame.service.DailyGameAttemptDetailsCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DailyChallengeResponseAssemblerTest {

    private final DailyChallengeResponseAssembler assembler = new DailyChallengeResponseAssembler();

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DailyGameType.class, names = {
            "MOVIE_BY_INFO", "SERIES_BY_INFO", "ACTOR_BY_MOVIE_FILMOGRAPHY", "ACTOR_BY_SERIES_FILMOGRAPHY"})
    @DisplayName("[images] Should Hide Open Information And Filmography Images Until Terminal")
    void shouldHideOpenInformationAndFilmographyImagesUntilTerminal(DailyGameType gameType) {
        DailyChallenge challenge = challenge(gameType, "/secret.jpg");
        List<DailyChallengeHint> legacyHints = List.of(hint(challenge, 1, "YEAR", "1999"));

        DailyGameStateDTO open = assembler.toState(challenge, null, legacyHints, true, true);

        assertThat(open.imageUrl()).isNull();
        assertThat(open.visibleImageUrls()).isEmpty();

        DailyGameStateDTO terminal = assembler.toState(
                challenge, result(0, DailyGameResultStatus.COMPLETED), legacyHints, true, true);

        assertThat(terminal.answer().imageUrl()).isEqualTo(switch (gameType) {
            case MOVIE_BY_INFO, SERIES_BY_INFO -> "https://image.tmdb.org/t/p/w500/secret.jpg";
            case ACTOR_BY_MOVIE_FILMOGRAPHY, ACTOR_BY_SERIES_FILMOGRAPHY
                    -> null;
            default -> throw new IllegalStateException("Unexpected game type: " + gameType);
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DailyGameType.class, names = {"MOVIE_BY_INFO", "SERIES_BY_INFO"})
    @DisplayName("[hints] Should Hide Legacy Hints For Information Games")
    void shouldHideLegacyHintsForInformationGames(DailyGameType gameType) {
        DailyChallenge challenge = challenge(gameType, "/secret.jpg");
        List<DailyChallengeHint> legacyHints = List.of(
                hint(challenge, 1, "YEAR", "1999"),
                hint(challenge, 2, "GENRE", "Drama"));

        DailyGameStateDTO notPlayed = assembler.toState(challenge, null, legacyHints, true, true);
        DailyGameStateDTO inProgress = assembler.toState(
                challenge, result(1, DailyGameResultStatus.IN_PROGRESS), legacyHints, true, true);

        assertThat(notPlayed.hints()).isEmpty();
        assertThat(inProgress.hints()).isEmpty();
    }

    @Test
    @DisplayName("[hints] Should Preserve The Allowed Hint Prefix For Poster Games")
    void shouldPreserveTheAllowedHintPrefixForPosterGames() {
        DailyChallenge challenge = challenge(DailyGameType.MOVIE_BY_POSTER, "/poster.jpg");
        List<DailyChallengeHint> legacyHints = List.of(
                hint(challenge, 1, "YEAR", "1999"),
                hint(challenge, 2, "GENRE", "Drama"));

        DailyGameStateDTO state = assembler.toState(
                challenge, result(0, DailyGameResultStatus.IN_PROGRESS), legacyHints, true, true);

        assertThat(state.hints()).extracting("hintValue").containsExactly("1999");
    }

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
                .displaySnapshot(Map.of("imagePaths", List.of(
                        "/third.jpg", "/second.jpg", "/first.jpg", "/fourth.jpg", "/fifth.jpg", "/sixth.jpg")))
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
                "https://image.tmdb.org/t/p/w300/first.jpg",
                "https://image.tmdb.org/t/p/w300/fourth.jpg",
                "https://image.tmdb.org/t/p/w300/fifth.jpg",
                "https://image.tmdb.org/t/p/w300/sixth.jpg");
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
                .displaySnapshot(Map.of("imagePaths", List.of(
                        "/third.jpg", "/second.jpg", "/first.jpg", "/fourth.jpg", "/fifth.jpg", "/sixth.jpg")))
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
    @DisplayName("[images] Should Reject A Stored Episode - When It Has Fewer Than Six Stills")
    void shouldRejectAStoredEpisodeWhenItHasFewerThanSixStills() {
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

        assertThatThrownBy(() -> assembler.toAttemptResponse(
                challenge, result(5, DailyGameResultStatus.IN_PROGRESS)))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.DailyGamesUnavailableException.class);
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

    @Test
    @DisplayName("[toState] Should Return All Movie Works Redacted With Metadata - When No Result Exists")
    void shouldReturnAllMovieWorksRedactedWithMetadataWhenNoResultExists() {
        DailyChallenge challenge = filmographyChallenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, List.of(
                filmographyWork("10", "First Movie", "movie", 2000),
                filmographyWork("20", "Second Movie", "movie", 2001),
                filmographyWork("30", "Third Movie", "movie", 2002)));

        DailyGameStateDTO state = assembler.toState(challenge, null, List.of(), true, true);

        assertThat(state.filmography().entries()).hasSize(3).allSatisfy(entry -> {
            assertThat(entry.title()).isNull();
            assertThat(entry.revealed()).isFalse();
            assertThat(entry.highlighted()).isFalse();
            assertThat(entry.year()).isNotNull();
            assertThat(entry.genres()).containsExactly("18");
            assertThat(entry.posterUrl()).isNull();
        });
        assertThat(state.filmography().entries()).extracting(DailyGameFilmographyEntryDTO::workId)
                .containsExactly("10", "20", "30");
    }

    @Test
    @DisplayName("[toState] Should Keep Only Eligible Major Series Works Redacted - When No Result Exists")
    void shouldKeepOnlyEligibleMajorSeriesWorksRedactedWhenNoResultExists() {
        DailyChallenge challenge = filmographyChallenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY, List.of(
                seriesFilmographyWork("10", "Major Series", 6, 2),
                seriesFilmographyWork("20", "Guest Series", 7, 2)));

        DailyGameStateDTO state = assembler.toState(challenge, null, List.of(), true, true);

        assertThat(state.filmography().majorRoles()).isTrue();
        assertThat(state.filmography().entries()).singleElement().satisfies(entry -> {
            assertThat(entry.workId()).isEqualTo("10");
            assertThat(entry.title()).isNull();
            assertThat(entry.revealed()).isFalse();
            assertThat(entry.highlighted()).isFalse();
            assertThat(entry.year()).isEqualTo(2000);
            assertThat(entry.genres()).containsExactly("18");
            assertThat(entry.posterUrl()).isNull();
        });
    }

    @Test
    @DisplayName("[toState] Should Reveal Only The Shared Movie Work - When An Attempt Contains Shared Keys")
    void shouldRevealOnlyTheSharedMovieWorkWhenAnAttemptContainsSharedKeys() {
        DailyChallenge challenge = filmographyChallenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, List.of(
                filmographyWork("10", "Secret Movie", "movie", 2000),
                filmographyWork("20", "Shared Movie", "movie", 2001),
                filmographyWork("30", "Other Movie", "movie", 2002)));
        UserDailyGameResult result = result();
        DailyGameFilmographyFeedbackDTO feedback = new DailyGameFilmographyFeedbackDTO(
                new DailyGameActorGuessDTO("2", "Guessed Actor"),
                List.of(), List.of("MOVIE:20"), List.of());
        result.setAttemptDetails(new DailyGameAttemptDetailsCodec().append(null,
                new DailyGameAttemptDTO(1, null, null, null, feedback)));

        DailyGameStateDTO state = assembler.toState(challenge, result, List.of(), true, true);

        assertThat(state.filmography().entries())
                .extracting(entry -> entry.workId() + ":" + entry.title() + ":"
                        + entry.revealed() + ":" + entry.highlighted())
                .containsExactly(
                        "10:null:false:false",
                        "20:Shared Movie:true:true",
                        "30:null:false:false");
    }

    private DailyGameInfoFeedbackDTO infoFeedback() {
        DailyGameComparisonCellDTO match = new DailyGameComparisonCellDTO(
                DailyGameComparisonStatus.MATCH, null, "value", List.of(), null);
        return new DailyGameInfoFeedbackDTO(match, match, match, match, match, match, match, match);
    }

    private Map<String, Object> filmographyWork(String workId, String title, String mediaType, int year) {
        return Map.of(
                "workId", workId,
                "title", title,
                "mediaType", mediaType,
                "year", year,
                "genres", List.of("18"),
                "posterUrl", "/" + workId + ".jpg",
                "period", year + "-01-01",
                "character", "Character");
    }

    private Map<String, Object> seriesFilmographyWork(
            String workId, String title, int totalEpisodes, int episodeCount) {
        Map<String, Object> work = new java.util.LinkedHashMap<>(
                filmographyWork(workId, title, "tv", 2000));
        work.put("episodeCount", episodeCount);
        work.put("totalEpisodes", totalEpisodes);
        return work;
    }

    private DailyChallenge filmographyChallenge(
            DailyGameType gameType, List<Map<String, Object>> filmography) {
        return DailyChallenge.builder()
                .id(UUID.randomUUID())
                .challengeDate(LocalDate.of(2026, 9, 27))
                .gameType(gameType)
                .targetKind(DailyGameTargetKind.PERSON)
                .targetTmdbId("1")
                .answerKey("PERSON:1")
                .imagePath("/actor.jpg")
                .answerSnapshot(Map.of("title", "Secret Actor", "filmography", filmography))
                .displaySnapshot(Map.of("imageUrl", "/actor.jpg"))
                .createdAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .build();
    }

    @Test
    @DisplayName("[sharing state] Should Default Sharing Flags To False - When No Result Exists")
    void shouldDefaultSharingFlagsToFalseWhenNoResultExists() {
        DailyChallenge challenge = challenge("/fight-club.jpg");

        DailyGameStateDTO state = assembler.toTodayResponse(
                LocalDate.of(2026, 9, 27), List.of(challenge), Map.of(), Map.of()).games().getFirst();

        assertThat(state.shareOnCompletion()).isFalse();
        assertThat(state.sharedToFeed()).isFalse();
    }

    @Test
    @DisplayName("[sharing state] Should Expose Persisted Sharing Flags - When A Result Exists")
    void shouldExposePersistedSharingFlagsWhenAResultExists() {
        DailyChallenge challenge = challenge("/fight-club.jpg");
        UserDailyGameResult result = result();
        result.setStatus(DailyGameResultStatus.COMPLETED);
        result.setCompletedAt(LocalDateTime.of(2026, 9, 27, 12, 0));
        result.setShareOnCompletion(true);
        result.markSharedAt(LocalDateTime.of(2026, 9, 27, 12, 0));

        DailyGameStateDTO state = assembler.toTodayResponse(
                LocalDate.of(2026, 9, 27), List.of(challenge), Map.of(challenge.getId(), result), Map.of())
                .games().getFirst();
        DailyGameAttemptResponseDTO attempt = assembler.toAttemptResponse(challenge, result);
        DailyGameHistoryDTO history = assembler.toHistoryResponse(LocalDate.of(2026, 9, 27), challenge, result);

        assertThat(state.shareOnCompletion()).isTrue();
        assertThat(state.sharedToFeed()).isTrue();
        assertThat(attempt.shareOnCompletion()).isTrue();
        assertThat(attempt.sharedToFeed()).isTrue();
        assertThat(history.sharedToFeed()).isTrue();
    }

    private DailyChallenge challenge(String imagePath) {
        return challenge(DailyGameType.MOVIE_BY_POSTER, imagePath);
    }

    private DailyChallenge challenge(DailyGameType gameType, String imagePath) {
        return DailyChallenge.builder()
                .id(UUID.randomUUID())
                .challengeDate(LocalDate.of(2026, 9, 27))
                .gameType(gameType)
                .targetKind(gameType.targetKind())
                .targetTmdbId("550")
                .answerKey("MOVIE:550")
                .imagePath(imagePath)
                .createdAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 27, 0, 0))
                .build();
    }

    private DailyChallengeHint hint(DailyChallenge challenge, int position, String type, String value) {
        return DailyChallengeHint.builder()
                .id(UUID.randomUUID())
                .dailyChallenge(challenge)
                .position(position)
                .hintType(type)
                .hintValue(value)
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
