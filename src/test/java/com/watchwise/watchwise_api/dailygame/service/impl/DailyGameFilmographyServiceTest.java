package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateRole;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregate;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregateCredit;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregateCredits;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DailyGameFilmographyServiceTest {

    private static final String LANGUAGE = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;

    @Mock
    private TmdbClient tmdbClient;

    private DailyGameFilmographyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DailyGameFilmographyServiceImpl(tmdbClient);
    }

    @Test
    @DisplayName("[compare] Should Return No Shared Works And Hide Titles - When Filmographies Do Not Intersect")
    void shouldReturnNoSharedWorksAndHideTitlesWhenFilmographiesDoNotIntersect() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY,
                List.of(work("100", "Secret Movie", "movie", 1999)));
        stubPerson("2", "Guessed Actor", credit("200", "Guessed Movie", "movie", "2000-01-01", null, null));

        DailyGameFilmographyFeedbackDTO feedback = service.compare(
                challenge, "2", true);

        assertThat(feedback.guessedActor().name()).isEqualTo("Guessed Actor");
        assertThat(feedback.sharedMajorRoleWorkKeys()).isEmpty();
        assertThat(feedback.sharedAllRoleWorkKeys()).isEmpty();
        assertThat(feedback.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.workId()).isEqualTo("100");
            assertThat(entry.title()).isNull();
            assertThat(entry.revealed()).isFalse();
            assertThat(entry.highlighted()).isFalse();
        });
    }

    @Test
    @DisplayName("[compare] Should Reveal One Shared Movie - When Filmographies Share One Work")
    void shouldRevealOneSharedMovieWhenFilmographiesShareOneWork() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, List.of(
                work("100", "Secret Movie", "movie", 1999),
                work("200", "Shared Movie", "movie", 2000)));
        stubPerson("2", "Guessed Actor",
                credit("200", "Shared Movie", "movie", "2000-01-01", null, null),
                credit("300", "Other Movie", "movie", "2001-01-01", null, null));

        DailyGameFilmographyFeedbackDTO feedback = service.compare(challenge, "2", true);

        assertThat(feedback.sharedMajorRoleWorkKeys()).containsExactly("MOVIE:200");
        assertThat(feedback.sharedAllRoleWorkKeys()).containsExactly("MOVIE:200");
        assertThat(feedback.entries()).extracting(entry -> entry.workId() + ":" + entry.title())
                .containsExactly("100:null", "200:Shared Movie");
        verify(tmdbClient).getPersonAggregate("2", LANGUAGE);
    }

    @Test
    @DisplayName("[compare] Should Return Multiple Shared Work Keys - When Filmographies Share Multiple Works")
    void shouldReturnMultipleSharedWorkKeysWhenFilmographiesShareMultipleWorks() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, List.of(
                work("100", "First Shared", "movie", 1999),
                work("200", "Second Shared", "movie", 2000),
                work("300", "Secret Only", "movie", 2001)));
        stubPerson("2", "Guessed Actor",
                credit("200", "Second Shared", "movie", "2000-01-01", null, null),
                credit("100", "First Shared", "movie", "1999-01-01", null, null));

        DailyGameFilmographyFeedbackDTO feedback = service.compare(challenge, "2", true);

        assertThat(feedback.sharedMajorRoleWorkKeys()).containsExactly("MOVIE:100", "MOVIE:200");
        assertThat(feedback.sharedAllRoleWorkKeys()).containsExactly("MOVIE:100", "MOVIE:200");
        assertThat(feedback.entries()).extracting(entry -> entry.workId() + ":" + entry.title())
                .containsExactly("100:First Shared", "200:Second Shared", "300:null");
    }

    @Test
    @DisplayName("[snapshot] Should Preserve Movie Aggregate Credits - When A Movie Filmography Is Loaded")
    void shouldPreserveMovieAggregateCreditsWhenAMovieFilmographyIsLoaded() {
        stubPerson("2", "Guessed Actor",
                credit("100", "First Shared", "movie", "1999-01-01", null, null),
                credit("200", "Second Shared", "movie", "2000-01-01", null, null));

        assertThat(service.snapshot("2", DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY).entries())
                .extracting(entry -> entry.get("workId"))
                .containsExactly("100", "200");
        assertThat(service.snapshot("2", DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY).entries())
                .extracting(entry -> entry.get("mediaType"))
                .containsExactly("movie", "movie");
    }

    @Test
    @DisplayName("[compare] Should Use Major Roles By Default - When A Series Has Guest And Major Shared Works")
    void shouldUseMajorRolesByDefaultWhenASeriesHasGuestAndMajorSharedWorks() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY, List.of(
                seriesWork("10", "Major Series", 6, 2),
                seriesWork("20", "Guest Series", 7, 2)));
        stubPerson("2", "Guessed Actor",
                credit("10", "Major Series", "tv", "2000-01-01", 6, 2),
                credit("20", "Guest Series", "tv", "2001-01-01", 7, 2));

        DailyGameFilmographyFeedbackDTO feedback = service.compare(challenge, "2", true);

        assertThat(feedback.sharedMajorRoleWorkKeys()).containsExactly("SERIES:10");
        assertThat(feedback.sharedAllRoleWorkKeys()).containsExactly("SERIES:10", "SERIES:20");
        assertThat(feedback.entries()).extracting(entry -> entry.workId() + ":" + entry.title())
                .containsExactly("10:Major Series");
    }

    @Test
    @DisplayName("[compare] Should Include All Participations - When Major Roles Are Disabled")
    void shouldIncludeAllParticipationsWhenMajorRolesAreDisabled() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY, List.of(
                seriesWork("10", "Major Series", 6, 2),
                seriesWork("20", "Guest Series", 7, 2)));
        stubPerson("2", "Guessed Actor",
                credit("10", "Major Series", "tv", "2000-01-01", 6, 2),
                credit("20", "Guest Series", "tv", "2001-01-01", 7, 2));

        DailyGameFilmographyFeedbackDTO feedback = service.compare(challenge, "2", false);

        assertThat(feedback.sharedMajorRoleWorkKeys()).containsExactly("SERIES:10");
        assertThat(feedback.sharedAllRoleWorkKeys()).containsExactly("SERIES:10", "SERIES:20");
        assertThat(feedback.entries()).extracting(entry -> entry.workId() + ":" + entry.title())
                .containsExactly("10:Major Series", "20:Guest Series");
    }

    @Test
    @DisplayName("[compare] Should Use TV Details As The Major Role Denominator - When Aggregate Episode Count Is Participation")
    void shouldUseTvDetailsAsTheMajorRoleDenominatorWhenAggregateEpisodeCountIsParticipation() {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY,
                List.of(seriesWork("10", "Long Series", 1, 1)));
        stubPerson("2", "Guessed Actor",
                credit("10", "Long Series", "tv", "2000-01-01", 1, 1));
        org.mockito.Mockito.lenient().when(tmdbClient.getTvFullDetails("10", LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(tvDetails("10", 21)));

        DailyGameFilmographyFeedbackDTO feedback = service.compare(challenge, "2", true);

        assertThat(feedback.sharedAllRoleWorkKeys()).containsExactly("SERIES:10");
        assertThat(feedback.sharedMajorRoleWorkKeys()).isEmpty();
    }

    @ParameterizedTest(name = "{0} total episodes requires {1} role episodes")
    @CsvSource({"6, 2", "7, 3", "20, 8", "21, 11"})
    @DisplayName("[compare] Should Apply The Proportional Major Role Threshold - At Each Boundary")
    void shouldApplyTheProportionalMajorRoleThresholdAtEachBoundary(int totalEpisodes, int minimumRoleEpisodes) {
        DailyChallenge challenge = challenge(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY,
                List.of(seriesWork("10", "Threshold Series", totalEpisodes, minimumRoleEpisodes)));
        stubPerson("2", "Guessed Actor",
                credit("10", "Threshold Series", "tv", "2000-01-01", totalEpisodes, minimumRoleEpisodes));

        DailyGameFilmographyFeedbackDTO feedback = service.compare(challenge, "2", true);

        assertThat(feedback.sharedMajorRoleWorkKeys()).containsExactly("SERIES:10");
    }

    private void stubPerson(String id, String name, TmdbPersonAggregateCredit... credits) {
        whenAggregate(id, new TmdbPersonAggregate(id, name, null, null, null, null, null,
                null, null, null, new TmdbPersonAggregateCredits(List.of(credits), List.of())));
        for (TmdbPersonAggregateCredit credit : credits) {
            if ("tv".equals(credit.mediaType())) {
                org.mockito.Mockito.when(tmdbClient.getTvFullDetails(credit.id(), LANGUAGE))
                        .thenReturn(new TmdbLookupResult.Found<>(tvDetails(credit.id(), credit.episodeCount())));
            }
        }
    }

    private void whenAggregate(String id, TmdbPersonAggregate aggregate) {
        org.mockito.Mockito.when(tmdbClient.getPersonAggregate(id, LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(aggregate));
    }

    private TmdbPersonAggregateCredit credit(String id, String title, String mediaType, String date,
                                             Integer totalEpisodes, Integer roleEpisodes) {
        return new TmdbPersonAggregateCredit(id, mediaType, title, null, "/" + id + ".jpg",
                "movie".equals(mediaType) ? date : null,
                "tv".equals(mediaType) ? date : null,
                null, null, List.of(), totalEpisodes,
                roleEpisodes == null ? List.of() : List.of(new TmdbAggregateRole("Role", roleEpisodes)));
    }

    private TmdbTvFullDetails tvDetails(String id, Integer numberOfEpisodes) {
        return new TmdbTvFullDetails(id, "Series " + id, "Series " + id, null, "/" + id + ".jpg", null,
                "2000-01-01", List.of(), List.of(), List.of(), List.of(), List.of(), null, null,
                null, null, 1, numberOfEpisodes, List.of(), null, "Ended");
    }

    private Map<String, Object> work(String id, String title, String mediaType, int year) {
        return Map.of(
                "workId", id,
                "title", title,
                "mediaType", mediaType,
                "year", year,
                "genres", List.of("18"),
                "posterUrl", "/" + id + ".jpg",
                "period", year + "-01-01",
                "character", "Character");
    }

    private Map<String, Object> seriesWork(String id, String title, int totalEpisodes, int roleEpisodes) {
        return Map.of(
                "workId", id,
                "title", title,
                "mediaType", "tv",
                "year", 2000,
                "genres", List.of("18"),
                "posterUrl", "/" + id + ".jpg",
                "episodeCount", roleEpisodes,
                "totalEpisodes", totalEpisodes,
                "period", "2000-01-01",
                "character", "Character");
    }

    private DailyChallenge challenge(DailyGameType gameType, List<Map<String, Object>> filmography) {
        return DailyChallenge.builder()
                .id(UUID.randomUUID())
                .challengeDate(LocalDate.of(2026, 9, 29))
                .gameType(gameType)
                .targetKind(DailyGameTargetKind.PERSON)
                .targetTmdbId("1")
                .answerKey("PERSON:1")
                .imagePath("/actor.jpg")
                .answerSnapshot(Map.of(
                        "targetKind", "PERSON",
                        "personTmdbId", "1",
                        "title", "Secret Actor",
                        "filmography", filmography))
                .displaySnapshot(Map.of("imageUrl", "/actor.jpg"))
                .createdAt(LocalDateTime.of(2026, 9, 29, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 29, 0, 0))
                .build();
    }
}
