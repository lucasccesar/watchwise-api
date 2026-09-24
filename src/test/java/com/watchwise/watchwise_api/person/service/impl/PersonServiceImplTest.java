package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregate;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregateCredit;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregateCredits;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.followedperson.service.FollowedPersonService;
import com.watchwise.watchwise_api.person.dto.PersonResponseDTO;
import com.watchwise.watchwise_api.person.dto.PersonCreditDTO;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private TmdbClient tmdbClient;
    @Mock private DiaryEntryRepository diaryEntryRepository;
    @Mock private UserListItemRepository userListItemRepository;
    @Mock private FollowedPersonService followedPersonService;

    private PersonServiceImpl service;
    private UUID viewerId;

    @BeforeEach
    void setUp() {
        service = new PersonServiceImpl(userRepository, tmdbClient, diaryEntryRepository, userListItemRepository,
                followedPersonService, new PageRequestFactory());
        viewerId = UUID.randomUUID();
        lenient().when(userRepository.findById(viewerId)).thenReturn(Optional.of(User.builder()
                .id(viewerId).preferredLanguage("pt-BR").build()));
        lenient().when(followedPersonService.isFollowing(viewerId, "287")).thenReturn(true);
        lenient().when(diaryEntryRepository.findWatchedMediaForPersonCredits(eq(viewerId), any())).thenReturn(List.of());
        lenient().when(userListItemRepository.findViewerMediaForPersonCredits(eq(viewerId), any())).thenReturn(List.of());
    }

    @Test
    void shouldMergeCastAndCrewCreditsAndIgnoreUnsupportedMedia() {
        stubAggregate(List.of(
                credit("1", "movie", "Alien", null, "/alien.jpg", "1979-05-25", "Ripley", null),
                credit("1", "movie", "Alien", null, "/alien.jpg", "1979-05-25", "Ellen Ripley", null),
                credit("2", "tv", null, "The Defenders", "/defenders.jpg", "2017-08-18", "Elektra", null),
                credit("3", "person", "Ignored", null, null, null, null, null)),
                List.of(
                        credit("1", "movie", "Alien", null, "/alien.jpg", "1979-05-25", null, "Director"),
                        credit("1", "movie", "Alien", null, "/alien.jpg", "1979-05-25", "Ripley", "Producer")));

        PersonResponseDTO result = service.getPerson(viewerId, "287", PersonParticipation.ALL, 1, 20);

        assertThat(result.person().name()).isEqualTo("Sigourney Weaver");
        assertThat(result.person().profileUrl()).isEqualTo("https://image.tmdb.org/t/p/w185/sigourney.jpg");
        assertThat(result.person().isFollowing()).isTrue();
        assertThat(result.credits().content()).hasSize(2);
        assertThat(result.credits().content()).filteredOn(credit -> credit.tmdbId().equals("1")).singleElement()
                .extracting("tmdbId", "title", "posterUrl", "releaseDate", "characters", "jobs", "participation")
                .containsExactly("1", "Alien", "https://image.tmdb.org/t/p/w500/alien.jpg", "1979-05-25",
                        List.of("Ripley", "Ellen Ripley"), List.of("Director", "Producer"), PersonParticipation.ALL);
    }

    @Test
    void shouldCalculateFullFilmographyProgressAndViewerStates() {
        stubAggregate(List.of(
                credit("1", "movie", "Watched movie", null, null, "2020-01-01", null, null),
                credit("2", "tv", null, "Started series", null, "2021-01-01", null, null),
                credit("3", "movie", "In list", null, null, "2022-01-01", null, null),
                credit("4", "movie", "Unrelated", null, null, "2023-01-01", null, null)), List.of());
        when(diaryEntryRepository.findWatchedMediaForPersonCredits(eq(viewerId), any())).thenReturn(List.of(
                media(DiaryEntryRepository.PersonCreditMedia.class, ContentType.MOVIE, "1", null),
                media(DiaryEntryRepository.PersonCreditMedia.class, ContentType.SERIES, "2", "2")));
        when(userListItemRepository.findViewerMediaForPersonCredits(eq(viewerId), any())).thenReturn(List.of(
                media(UserListItemRepository.PersonCreditMedia.class, ContentType.MOVIE, "3", null)));

        PersonResponseDTO result = service.getPerson(viewerId, "287", PersonParticipation.ALL, 1, 20);

        assertThat(result.progress()).extracting("totalCredits", "watchedCredits", "watchedPercentage")
                .containsExactly(4, 2, 50.0d);
        assertThat(result.credits().content()).filteredOn(credit -> credit.tmdbId().equals("3")).singleElement()
                .extracting("isWatched", "isInList").containsExactly(false, true);
        assertThat(result.credits().content()).filteredOn(credit -> credit.tmdbId().equals("2")).singleElement()
                .extracting("isWatched", "isInList").containsExactly(true, false);
    }

    @Test
    void shouldFilterAfterMergeAndPaginateWithStableOrdering() {
        stubAggregate(List.of(
                credit("1", "movie", "Current", null, null, "2024-01-01", "Cast", null),
                credit("2", "movie", "Old", null, null, "1999-01-01", "Cast", null),
                credit("3", "movie", "Unknown", null, null, null, "Cast", null)),
                List.of(credit("1", "movie", "Current", null, null, "2024-01-01", null, "Director")));

        PersonResponseDTO result = service.getPerson(viewerId, "287", PersonParticipation.CREW, 1, 1);

        assertThat(result.progress().totalCredits()).isEqualTo(3);
        assertThat(result.credits()).extracting("page", "size", "totalElements", "totalPages", "hasNext")
                .containsExactly(1, 1, 1L, 1, false);
        assertThat(result.credits().content()).singleElement().extracting("tmdbId", "participation", "jobs")
                .containsExactly("1", PersonParticipation.ALL, List.of("Director"));
    }

    @Test
    void shouldKeepCastParticipationWhenCharacterIsMissing() {
        stubAggregate(List.of(
                credit("1", "movie", "No character", null, null, "2020-01-01", null, null)), List.of());

        PersonResponseDTO castResult = service.getPerson(viewerId, "287", PersonParticipation.CAST, 1, 20);
        PersonResponseDTO crewResult = service.getPerson(viewerId, "287", PersonParticipation.CREW, 1, 20);

        assertThat(castResult.credits().content()).singleElement()
                .extracting(PersonCreditDTO::participation, PersonCreditDTO::characters, PersonCreditDTO::jobs)
                .containsExactly(PersonParticipation.CAST, List.of(), List.of());
        assertThat(crewResult.credits().content()).isEmpty();
    }

    @Test
    void shouldUseParsedDatesAndPlaceInvalidDatesLast() {
        stubAggregate(List.of(
                credit("1", "movie", "Current", null, null, "2024-01-01", null, null),
                credit("2", "movie", "Old", null, null, "1999-01-01", null, null),
                credit("3", "movie", "Malformed", null, null, "2024-13-40", null, null),
                credit("4", "movie", "Partial", null, null, "2024-01", null, null),
                credit("5", "movie", "Blank", null, null, "   ", null, null),
                credit("6", "movie", "Missing", null, null, null, null, null)), List.of());

        PersonResponseDTO result = service.getPerson(viewerId, "287", PersonParticipation.ALL, 1, 20);

        assertThat(result.credits().content()).extracting(PersonCreditDTO::tmdbId, PersonCreditDTO::releaseDate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1", "2024-01-01"),
                        org.assertj.core.groups.Tuple.tuple("2", "1999-01-01"),
                        org.assertj.core.groups.Tuple.tuple("5", null),
                        org.assertj.core.groups.Tuple.tuple("3", null),
                        org.assertj.core.groups.Tuple.tuple("6", null),
                        org.assertj.core.groups.Tuple.tuple("4", null));
    }

    @Test
    void shouldOrderEqualAndNullDatesByCaseInsensitiveTitleTypeAndTmdbId() {
        stubAggregate(List.of(
                credit("40", "movie", "alpha", null, null, "2024-01-01", null, null),
                credit("30", "tv", null, "Alpha", null, "2024-01-01", null, null),
                credit("10", "movie", "ALPHA", null, null, "2024-01-01", null, null),
                credit("20", "movie", "Beta", null, null, "2024-01-01", null, null),
                credit("60", "movie", "Zulu", null, null, null, null, null),
                credit("50", "movie", "Aaron", null, null, null, null, null)), List.of());

        PersonResponseDTO result = service.getPerson(viewerId, "287", PersonParticipation.ALL, 1, 20);

        assertThat(result.credits().content()).extracting(PersonCreditDTO::tmdbId)
                .containsExactly("10", "40", "30", "20", "50", "60");
    }

    @Test
    void shouldIgnoreCreditsWithNonNumericTmdbIds() {
        stubAggregate(List.of(
                credit("abc", "movie", "Malformed", null, null, "2024-01-01", null, null),
                credit("123", "movie", "Valid", null, null, "2023-01-01", null, null)), List.of());

        PersonResponseDTO result = service.getPerson(viewerId, "287", PersonParticipation.ALL, 1, 20);

        assertThat(result.progress().totalCredits()).isEqualTo(1);
        assertThat(result.credits().content()).extracting(PersonCreditDTO::tmdbId).containsExactly("123");
    }

    @Test
    void shouldUseLaterDuplicateMetadataWhenFirstCreditOmitsIt() {
        stubAggregate(List.of(
                credit("1", "movie", null, null, null, null, null, null)), List.of(
                credit("1", "movie", "Recovered title", null, "/recovered.jpg", "2001-02-03", null, "Director")));

        PersonCreditDTO result = service.getPerson(viewerId, "287", PersonParticipation.ALL, 1, 20)
                .credits().content().getFirst();

        assertThat(result).extracting(PersonCreditDTO::title, PersonCreditDTO::posterUrl,
                        PersonCreditDTO::releaseDate, PersonCreditDTO::participation)
                .containsExactly("Recovered title", "https://image.tmdb.org/t/p/w500/recovered.jpg",
                        "2001-02-03", PersonParticipation.ALL);
    }

    @Test
    void shouldReturnEmptyPageWhenPageOffsetExceedsIntegerRange() {
        stubAggregate(List.of(credit("1", "movie", "Movie", null, null, "2020-01-01", null, null)), List.of());

        PersonResponseDTO result = service.getPerson(viewerId, "287", PersonParticipation.ALL, Integer.MAX_VALUE, 1000);

        assertThat(result.credits().content()).isEmpty();
        assertThat(result.credits().page()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void shouldBatchLocalStateQueriesBeyondFiveHundredCredits() {
        List<TmdbPersonAggregateCredit> credits = IntStream.rangeClosed(1, 501)
                .mapToObj(id -> credit(String.valueOf(id), "movie", "Movie " + id, null, null,
                        "2020-01-01", null, null))
                .toList();
        stubAggregate(credits, List.of());

        service.getPerson(viewerId, "287", PersonParticipation.ALL, 1, 20);

        verify(diaryEntryRepository, times(2)).findWatchedMediaForPersonCredits(eq(viewerId),
                argThat(ids -> ids.size() <= 500));
        verify(userListItemRepository, times(2)).findViewerMediaForPersonCredits(eq(viewerId),
                argThat(ids -> ids.size() <= 500));
    }

    @Test
    void shouldSkipLocalStateQueriesForEmptyFilmography() {
        stubAggregate(List.of(), List.of());

        PersonResponseDTO result = service.getPerson(viewerId, "287", PersonParticipation.ALL, 1, 20);

        assertThat(result.progress()).extracting("totalCredits", "watchedCredits", "watchedPercentage")
                .containsExactly(0, 0, 0.0d);
        verifyNoInteractions(diaryEntryRepository, userListItemRepository);
    }

    @Test
    void shouldMapSeasonAndEpisodeStatesToTheirParentSeries() {
        stubAggregate(List.of(credit("2", "tv", null, "Series", null, "2020-01-01", null, null)), List.of());
        when(diaryEntryRepository.findWatchedMediaForPersonCredits(eq(viewerId), any())).thenReturn(List.of(
                media(DiaryEntryRepository.PersonCreditMedia.class, ContentType.SEASON, null, "2")));
        when(userListItemRepository.findViewerMediaForPersonCredits(eq(viewerId), any())).thenReturn(List.of(
                media(UserListItemRepository.PersonCreditMedia.class, ContentType.EPISODE, null, "2")));

        PersonCreditDTO result = service.getPerson(viewerId, "287", PersonParticipation.ALL, 1, 20)
                .credits().content().getFirst();

        assertThat(result).extracting(PersonCreditDTO::isWatched, PersonCreditDTO::isInList)
                .containsExactly(true, true);
    }

    @Test
    void shouldMapTypedTmdbErrorsAndRejectInvalidTmdbId() {
        assertThatThrownBy(() -> service.getPerson(viewerId, " ", null, 1, 20))
                .isInstanceOf(BadRequestException.class);

        when(tmdbClient.getPersonAggregate("404", "pt-BR")).thenReturn(new TmdbLookupResult.NotFound<>());
        assertThatThrownBy(() -> service.getPerson(viewerId, "404", null, 1, 20))
                .isInstanceOf(NotFoundException.class);

        when(tmdbClient.getPersonAggregate("503", "pt-BR")).thenReturn(new TmdbLookupResult.Unavailable<>());
        assertThatThrownBy(() -> service.getPerson(viewerId, "503", null, 1, 20))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    void shouldMapNullSuccessfulAggregateToTmdbUnavailable() {
        when(tmdbClient.getPersonAggregate("503", "pt-BR")).thenReturn(new TmdbLookupResult.Found<>(null));

        assertThatThrownBy(() -> service.getPerson(viewerId, "503", null, 1, 20))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    private void stubAggregate(List<TmdbPersonAggregateCredit> cast, List<TmdbPersonAggregateCredit> crew) {
        when(tmdbClient.getPersonAggregate("287", "pt-BR")).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbPersonAggregate("287", "Sigourney Weaver", "Bio", "1949-10-08", null, "New York", "female",
                        "/sigourney.jpg", "Acting", List.of("Susan"), new TmdbPersonAggregateCredits(cast, crew))));
    }

    private TmdbPersonAggregateCredit credit(String id, String mediaType, String title, String name, String posterPath,
                                              String date, String character, String job) {
        return new TmdbPersonAggregateCredit(id, mediaType, title, name, posterPath,
                "movie".equals(mediaType) ? date : null, "tv".equals(mediaType) ? date : null, character, job);
    }

    @SuppressWarnings("unchecked")
    private <T> T media(Class<T> projection, ContentType type, String tmdbId, String seriesTmdbId) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{projection},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> type;
                    case "getTmdbId" -> tmdbId;
                    case "getSeriesTmdbId" -> seriesTmdbId;
                    default -> null;
                });
    }
}
