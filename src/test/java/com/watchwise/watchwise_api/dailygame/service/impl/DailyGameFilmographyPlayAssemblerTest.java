package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameActorGuessDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameCandidateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DailyGameFilmographyPlayAssemblerTest {

    private final DailyGameFilmographyPlayAssembler assembler = new DailyGameFilmographyPlayAssembler();

    @Test
    @DisplayName("[assemble] Should Project Actor Profile, Age, Shared Counts And Revealed Movies")
    void shouldProjectActorProfileAgeSharedCountsAndRevealedMovies() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, List.of(
                work("200", "Second Movie", "movie", 2020, 1, null,
                        "2020-01-01", "Second Role"),
                work("100", "First Movie", "movie", 1990, 2, null,
                        "1990-01-01", "First Role"),
                work("300", "Hidden Movie", "movie", 1980, 1, null,
                        "1980-01-01", "Hidden Role")));
        DailyGameFilmographyFeedbackDTO feedback = feedback("2", "Actor One",
                List.of("MOVIE:100"), List.of("MOVIE:100", "MOVIE:200"));
        DailyGameAttemptDTO attempt = attempt("2", "Actor One", "/actor.jpg", "1964-10-16", feedback);

        var state = assembler.assemble(challenge, List.of(attempt), true, 10);

        assertThat(state.majorRoles()).isFalse();
        assertThat(state.guessedActors()).singleElement().satisfies(actor -> {
            assertThat(actor.personTmdbId()).isEqualTo("2");
            assertThat(actor.name()).isEqualTo("Actor One");
            assertThat(actor.profileUrl()).isEqualTo("/actor.jpg");
            assertThat(actor.ageAtChallengeDate()).isEqualTo(61);
            assertThat(actor.sharedWorksCount()).isEqualTo(2);
            assertThat(actor.majorRoleSharedWorksCount()).isEqualTo(1);
        });
        assertThat(state.entries()).extracting(entry -> entry.workId() + ":" + entry.title())
                .containsExactly("200:Second Movie", "100:First Movie", "300:null");
        assertThat(state.entries()).filteredOn(entry -> entry.workId().equals("100")).singleElement().satisfies(entry -> {
            assertThat(entry.revealed()).isTrue();
            assertThat(entry.highlighted()).isTrue();
            assertThat(entry.posterUrl()).isEqualTo("/100.jpg");
            assertThat(entry.character()).isEqualTo("First Role");
            assertThat(entry.period()).isEqualTo("1990-01-01");
            assertThat(entry.sharedWith()).containsExactly("Actor One");
        });
        assertThat(state.entries()).filteredOn(entry -> entry.workId().equals("300")).singleElement().satisfies(entry -> {
            assertThat(entry.posterUrl()).isNull();
            assertThat(entry.character()).isNull();
            assertThat(entry.sharedWith()).isEmpty();
        });
        assertThat(state.attemptsUsed()).isEqualTo(1);
        assertThat(state.attemptsRemaining()).isEqualTo(9);
        assertThat(state.maxAttempts()).isEqualTo(10);
    }

    @Test
    @DisplayName("[assemble] Should Filter Series Rows By Major Roles And Preserve Both Shared Counts")
    void shouldFilterSeriesRowsByMajorRolesAndPreserveBothSharedCounts() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY, List.of(
                work("100", "Major Series", "tv", 2020, 5, 10,
                        "2018-01-01/2022-01-01", "Lead"),
                work("200", "Guest Series", "tv", 2019, 2, 10,
                        "2019-01-01/2019-01-01", "Guest")));
        DailyGameFilmographyFeedbackDTO feedback = feedback("3", "Actor Two",
                List.of("SERIES:100"), List.of("SERIES:100", "SERIES:200"));
        DailyGameAttemptDTO attempt = attempt("3", "Actor Two", "/actor-two.jpg", null, feedback);

        var major = assembler.assemble(challenge, List.of(attempt), true, 10);
        var all = assembler.assemble(challenge, List.of(attempt), false, 10);

        assertThat(major.majorRoles()).isTrue();
        assertThat(major.guessedActors()).singleElement().satisfies(actor -> {
            assertThat(actor.sharedWorksCount()).isEqualTo(2);
            assertThat(actor.majorRoleSharedWorksCount()).isEqualTo(1);
            assertThat(actor.ageAtChallengeDate()).isNull();
        });
        assertThat(major.entries()).extracting(entry -> entry.workId() + ":" + entry.title())
                .containsExactly("100:Major Series");
        assertThat(major.entries().getFirst()).satisfies(entry -> {
            assertThat(entry.episodeCount()).isEqualTo(5);
            assertThat(entry.period()).isEqualTo("2018-01-01/2022-01-01");
            assertThat(entry.sharedWith()).containsExactly("Actor Two");
        });
        assertThat(all.majorRoles()).isFalse();
        assertThat(all.entries()).extracting(entry -> entry.workId() + ":" + entry.title())
                .containsExactly("100:Major Series", "200:Guest Series");
        assertThat(all.entries().get(1).sharedWith()).containsExactly("Actor Two");
    }

    @Test
    @DisplayName("[assemble] Should Keep Legacy Missing Period Null And Redact Unrevealed Metadata")
    void shouldKeepLegacyMissingPeriodNullAndRedactUnrevealedMetadata() {
        Map<String, Object> legacyWork = Map.of(
                "workId", "10", "title", "Legacy Movie", "mediaType", "movie", "year", 2001,
                "genres", List.of("18"), "posterUrl", "/legacy.jpg", "character", "Legacy Role");
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, List.of(legacyWork));

        var state = assembler.assemble(challenge, List.of(), true, 6);

        assertThat(state.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.period()).isNull();
            assertThat(entry.title()).isNull();
            assertThat(entry.posterUrl()).isNull();
            assertThat(entry.character()).isNull();
            assertThat(entry.year()).isEqualTo(2001);
            assertThat(entry.genres()).containsExactly("18");
        });
    }

    @Test
    @DisplayName("[assemble] Should Ignore Malformed Snapshot Entries And Handle Missing Candidate Dates")
    void shouldIgnoreMalformedSnapshotEntriesAndHandleMissingCandidateDates() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, java.util.Arrays.asList(
                null,
                Map.of("title", "Missing ID", "mediaType", "movie"),
                work("10", "Valid Movie", "movie", 2000, null, null, null, null)));
        DailyGameFilmographyFeedbackDTO feedback = feedback("4", "Actor Three", List.of(), List.of());

        var state = assembler.assemble(challenge,
                List.of(new DailyGameAttemptDTO(1, null, null, null, feedback)), true, 6);

        assertThat(state.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.workId()).isEqualTo("10");
            assertThat(entry.title()).isNull();
        });
        assertThat(state.guessedActors()).singleElement().satisfies(actor -> {
            assertThat(actor.name()).isEqualTo("Actor Three");
            assertThat(actor.profileUrl()).isNull();
            assertThat(actor.ageAtChallengeDate()).isNull();
        });
    }

    private DailyChallenge challenge(DailyGameType gameType, List<?> filmography) {
        return DailyChallenge.builder()
                .id(UUID.randomUUID())
                .challengeDate(LocalDate.of(2026, 10, 15))
                .gameType(gameType)
                .targetKind(DailyGameTargetKind.PERSON)
                .targetTmdbId("1")
                .answerKey("PERSON:1")
                .imagePath("/secret.jpg")
                .answerSnapshot(Map.of("filmography", filmography))
                .displaySnapshot(Map.of("imageUrl", "/secret.jpg"))
                .createdAt(LocalDate.of(2026, 10, 15).atStartOfDay())
                .updatedAt(LocalDate.of(2026, 10, 15).atStartOfDay())
                .build();
    }

    private Map<String, Object> work(String id, String title, String mediaType, int year,
                                     Integer episodeCount, Integer totalEpisodes, String period, String character) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("workId", id);
        map.put("title", title);
        map.put("mediaType", mediaType);
        map.put("year", year);
        map.put("genres", List.of("18"));
        map.put("posterUrl", "/" + id + ".jpg");
        map.put("episodeCount", episodeCount);
        map.put("totalEpisodes", totalEpisodes);
        map.put("period", period);
        map.put("character", character);
        return map;
    }

    private DailyGameFilmographyFeedbackDTO feedback(String actorId, String actorName,
                                                      List<String> majorKeys, List<String> allKeys) {
        return new DailyGameFilmographyFeedbackDTO(
                new DailyGameActorGuessDTO(actorId, actorName), majorKeys, allKeys, List.of());
    }

    private DailyGameAttemptDTO attempt(String actorId, String actorName, String imageUrl,
                                        String birthDate, DailyGameFilmographyFeedbackDTO feedback) {
        DailyGameCandidateDTO candidate = new DailyGameCandidateDTO(
                DailyGameTargetKind.PERSON, null, actorId, null, null, null,
                actorName, imageUrl, birthDate == null ? null : LocalDate.parse(birthDate));
        return new DailyGameAttemptDTO(1, candidate, null, null, feedback);
    }
}
