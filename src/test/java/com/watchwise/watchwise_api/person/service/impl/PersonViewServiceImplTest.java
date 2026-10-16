package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregate;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregateCredit;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregateCredits;
import com.watchwise.watchwise_api.content.dto.ContentCardStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.followedperson.repository.FollowedPersonRepository;
import com.watchwise.watchwise_api.followedperson.service.FollowedPersonService;
import com.watchwise.watchwise_api.person.dto.PersonCreditSort;
import com.watchwise.watchwise_api.person.dto.PersonCreditSortDirection;
import com.watchwise.watchwise_api.person.dto.PersonCreditViewDTO;
import com.watchwise.watchwise_api.person.dto.PersonViewDTO;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonViewServiceImplTest {

    @Test
    void shouldApplyWatchFiltersBeforeSortingAndPageSlicing() {
        UUID viewerId = UUID.randomUUID();
        var userRepository = mock(UserRepository.class);
        var followerRepository = mock(FollowedPersonRepository.class);
        var followedPersonService = mock(FollowedPersonService.class);
        var aggregateReader = mock(PersonAggregateReader.class);
        var assembler = mock(PersonCreditViewAssembler.class);
        var aggregate = new TmdbPersonAggregate("287", "Person", null, null, null, null, null,
                null, "Acting", List.of(), new TmdbPersonAggregateCredits(List.of(
                        credit("1", "Newest", "2024-01-01"), credit("2", "Match", "2022-01-01"),
                        credit("3", "Older match", "2020-01-01")), List.of()));
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(User.builder().id(viewerId).build()));
        when(aggregateReader.read("287", "en-US")).thenReturn(aggregate);
        when(followerRepository.countByPersonTmdbId("287")).thenReturn(18L);
        when(followedPersonService.isFollowing(viewerId, "287")).thenReturn(true);
        var cards = List.of(
                card("1", "Newest", "2024-01-01", WatchStatus.UNWATCHED, true),
                card("2", "Match", "2022-01-01", WatchStatus.WATCHED, true),
                card("3", "Older match", "2020-01-01", WatchStatus.WATCHED, false));
        when(assembler.assemble(org.mockito.ArgumentMatchers.eq(viewerId), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(cards);

        var view = new PersonViewServiceImpl(userRepository, followerRepository, followedPersonService,
                aggregateReader, new PersonFilmographyNormalizer(), assembler,
                new com.watchwise.watchwise_api.common.pagination.PageRequestFactory())
                .getPersonView(viewerId, "287", PersonParticipation.ALL, WatchStatus.WATCHED, true,
                        PersonCreditSort.RELEASE_DATE, PersonCreditSortDirection.DESC, 1, 1);

        assertThat(view.followersCount()).isEqualTo(18L);
        assertThat(view.person().isFollowing()).isTrue();
        assertThat(view.hero().tmdbId()).isEqualTo("1");
        assertThat(view.credits().content()).extracting(PersonCreditViewDTO::tmdbId).containsExactly("2");
        assertThat(view.credits()).extracting("totalElements", "page", "size").containsExactly(1L, 1, 1);
        verify(assembler).assemble(viewerId, new PersonFilmographyNormalizer().normalize(aggregate));
    }

    @Test
    void shouldReturnNoHeroWhenNoNormalizedCreditHasUsableBackdrop() {
        UUID viewerId = UUID.randomUUID();
        var userRepository = mock(UserRepository.class);
        var followerRepository = mock(FollowedPersonRepository.class);
        var followedPersonService = mock(FollowedPersonService.class);
        var aggregateReader = mock(PersonAggregateReader.class);
        var assembler = mock(PersonCreditViewAssembler.class);
        var aggregate = new TmdbPersonAggregate("287", "Person", null, null, null, null, null,
                null, null, List.of(), new TmdbPersonAggregateCredits(List.of(
                        new TmdbPersonAggregateCredit("1", "movie", "No backdrop", null, null, null,
                                null, null, null)), List.of()));
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(User.builder().id(viewerId).build()));
        when(aggregateReader.read("287", "en-US")).thenReturn(aggregate);
        when(assembler.assemble(org.mockito.ArgumentMatchers.eq(viewerId), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());

        var service = new PersonViewServiceImpl(userRepository, followerRepository, followedPersonService,
                aggregateReader, new PersonFilmographyNormalizer(), assembler,
                new com.watchwise.watchwise_api.common.pagination.PageRequestFactory());
        var view = service.getPersonView(viewerId, "287", PersonParticipation.ALL, null, null, null, null, null, null);
        var capped = service.getPersonView(viewerId, "287", PersonParticipation.ALL, null, null, null, null, 1, 100);

        assertThat(view.hero()).isNull();
        assertThat(view.credits().size()).isEqualTo(24);
        assertThat(capped.credits().size()).isEqualTo(24);
    }

    @Test
    void shouldKeepNullAverageScoresLastAndUseStableTieBreakersInBothDirections() {
        UUID viewerId = UUID.randomUUID();
        var cards = List.of(
                scoredCard("3", MovieOrSeriesType.MOVIE, "Alpha", 8.0),
                scoredCard("2", MovieOrSeriesType.MOVIE, "alpha", 8.0),
                scoredCard("1", MovieOrSeriesType.SERIES, "Alpha", 8.0),
                scoredCard("4", MovieOrSeriesType.MOVIE, "Aardvark", 9.0),
                scoredCard("5", MovieOrSeriesType.MOVIE, "Missing", null));

        PersonViewDTO descending = sortedView(viewerId, cards, PersonCreditSort.AVERAGE_SCORE, PersonCreditSortDirection.DESC);
        PersonViewDTO ascending = sortedView(viewerId, cards, PersonCreditSort.AVERAGE_SCORE, PersonCreditSortDirection.ASC);

        assertThat(descending.credits().content()).extracting(PersonCreditViewDTO::tmdbId)
                .containsExactly("4", "2", "3", "1", "5");
        assertThat(ascending.credits().content()).extracting(PersonCreditViewDTO::tmdbId)
                .containsExactly("2", "3", "1", "4", "5");
    }

    @Test
    void shouldKeepMissingReleasePopularityAndEpisodeCountsLastInBothDirections() {
        UUID viewerId = UUID.randomUUID();
        var cards = List.of(
                sortableCard("1", "Early", LocalDate.of(2020, 1, 1), 2.0, 1, 6.0),
                sortableCard("2", "Late", LocalDate.of(2022, 1, 1), 9.0, 4, 9.0),
                sortableCard("3", "Unknown", null, null, null, null));
        for (PersonCreditSort sort : List.of(PersonCreditSort.RELEASE_DATE, PersonCreditSort.POPULARITY,
                PersonCreditSort.CREDITED_EPISODE_COUNT)) {
            var descending = sortedView(viewerId, cards, sort, PersonCreditSortDirection.DESC);
            var ascending = sortedView(viewerId, cards, sort, PersonCreditSortDirection.ASC);
            assertThat(descending.credits().content()).extracting(PersonCreditViewDTO::tmdbId)
                    .as("descending %s", sort).containsExactly("2", "1", "3");
            assertThat(ascending.credits().content()).extracting(PersonCreditViewDTO::tmdbId)
                    .as("ascending %s", sort).containsExactly("1", "2", "3");
        }
    }

    @Test
    void shouldChooseHeroByBackdropPopularityDateTitleTypeAndId() {
        UUID viewerId = UUID.randomUUID();
        var userRepository = mock(UserRepository.class);
        var followerRepository = mock(FollowedPersonRepository.class);
        var followedPersonService = mock(FollowedPersonService.class);
        var aggregateReader = mock(PersonAggregateReader.class);
        var assembler = mock(PersonCreditViewAssembler.class);
        var cast = List.of(
                heroCredit("1", "movie", "Unusable", "2025-01-01", 100.0, " "),
                heroCredit("8", "movie", "Zulu", "2020-01-01", 10.0, "/z.jpg"),
                heroCredit("7", "tv", "Beta", "2021-01-01", 10.0, "/b.jpg"),
                heroCredit("6", "movie", "zeta", "2021-01-01", 10.0, "/zeta.jpg"),
                heroCredit("5", "movie", "Alpha", "2021-01-01", 10.0, "/alpha5.jpg"),
                heroCredit("4", "movie", "alpha", "2021-01-01", 10.0, "/alpha4.jpg"),
                heroCredit("1", "tv", "alpha", "2021-01-01", 10.0, "/series.jpg"));
        var aggregate = new TmdbPersonAggregate("287", "Person", null, null, null, null, null,
                null, null, List.of(), new TmdbPersonAggregateCredits(cast, List.of()));
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(User.builder().id(viewerId).build()));
        when(aggregateReader.read("287", "en-US")).thenReturn(aggregate);
        when(assembler.assemble(org.mockito.ArgumentMatchers.eq(viewerId), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());

        var view = new PersonViewServiceImpl(userRepository, followerRepository, followedPersonService,
                aggregateReader, new PersonFilmographyNormalizer(), assembler,
                new com.watchwise.watchwise_api.common.pagination.PageRequestFactory())
                .getPersonView(viewerId, "287", PersonParticipation.ALL, null, null, null, null, 1, 24);

        assertThat(view.hero()).extracting("tmdbId", "backdropUrl")
                .containsExactly("4", "https://image.tmdb.org/t/p/w1280/alpha4.jpg");
    }

    private static TmdbPersonAggregateCredit credit(String id, String title, String date) {
        return new TmdbPersonAggregateCredit(id, "movie", title, null, "/poster.jpg", date, null,
                "Character", null, null, null, null, "/backdrop.jpg", 1.0);
    }

    private static TmdbPersonAggregateCredit heroCredit(String id, String mediaType, String title,
                                                         String date, Double popularity, String backdrop) {
        return new TmdbPersonAggregateCredit(id, mediaType, mediaType.equals("movie") ? title : null,
                mediaType.equals("tv") ? title : null, "/poster.jpg", date, date, null, null,
                null, null, null, backdrop, popularity);
    }

    private static PersonCreditViewDTO card(String id, String title, String date, WatchStatus status,
                                             boolean inWatchlist) {
        return new PersonCreditViewDTO(id, MovieOrSeriesType.MOVIE, title, null, LocalDate.parse(date),
                List.of("Character"), List.of(), PersonParticipation.CAST, null, 1.0,
                new ContentCardStatsDTO(null, 0L, 0L, 0L),
                new ContentCardViewerStateDTO(status, null, null, null, null, 0,
                        inWatchlist, false, null, null), false, ContentPreviewStatus.AVAILABLE);
    }

    private static PersonCreditViewDTO scoredCard(String id, MovieOrSeriesType type, String title, Double score) {
        return new PersonCreditViewDTO(id, type, title, null, LocalDate.of(2020, 1, 1), List.of(), List.of(),
                PersonParticipation.CAST, null, 1.0,
                new com.watchwise.watchwise_api.content.dto.ContentCardStatsDTO(score, 0L, 0L, 0L),
                new com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO(WatchStatus.UNWATCHED,
                        null, null, null, null, 0, false, false, null, null), false,
                ContentPreviewStatus.AVAILABLE);
    }

    private static PersonCreditViewDTO sortableCard(String id, String title, LocalDate releaseDate,
                                                     Double popularity, Integer episodes, Double score) {
        return new PersonCreditViewDTO(id, MovieOrSeriesType.MOVIE, title, null, releaseDate, List.of(), List.of(),
                PersonParticipation.CAST, episodes, popularity,
                new com.watchwise.watchwise_api.content.dto.ContentCardStatsDTO(score, 0L, 0L, 0L),
                new com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO(WatchStatus.UNWATCHED,
                        null, null, null, null, 0, false, false, null, null), false,
                ContentPreviewStatus.AVAILABLE);
    }

    private static PersonViewDTO sortedView(UUID viewerId, List<PersonCreditViewDTO> cards, PersonCreditSort sort,
                                             PersonCreditSortDirection direction) {
        var userRepository = mock(UserRepository.class);
        var followerRepository = mock(FollowedPersonRepository.class);
        var followedPersonService = mock(FollowedPersonService.class);
        var aggregateReader = mock(PersonAggregateReader.class);
        var assembler = mock(PersonCreditViewAssembler.class);
        var cast = cards.stream().map(card -> new TmdbPersonAggregateCredit(card.tmdbId(),
                card.type() == MovieOrSeriesType.MOVIE ? "movie" : "tv",
                card.type() == MovieOrSeriesType.MOVIE ? card.title() : null,
                card.type() == MovieOrSeriesType.SERIES ? card.title() : null,
                null, "2020-01-01", "2020-01-01", null, null)).toList();
        var aggregate = new TmdbPersonAggregate("287", "Person", null, null, null, null, null,
                null, null, List.of(), new TmdbPersonAggregateCredits(cast, List.of()));
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(User.builder().id(viewerId).build()));
        when(aggregateReader.read("287", "en-US")).thenReturn(aggregate);
        when(assembler.assemble(org.mockito.ArgumentMatchers.eq(viewerId), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(cards);
        return new PersonViewServiceImpl(userRepository, followerRepository, followedPersonService,
                aggregateReader, new PersonFilmographyNormalizer(), assembler,
                new com.watchwise.watchwise_api.common.pagination.PageRequestFactory())
                .getPersonView(viewerId, "287", PersonParticipation.ALL, null, null,
                        sort, direction, 1, 24);
    }
}
