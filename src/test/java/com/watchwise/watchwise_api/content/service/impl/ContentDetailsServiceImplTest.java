package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCredits;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCrewJob;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCrewMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateRole;
import com.watchwise.watchwise_api.common.tmdb.TmdbAlternativeTitleEntry;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbCreator;
import com.watchwise.watchwise_api.common.tmdb.TmdbCrewMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbGuestStar;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieAlternativeTitles;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCompany;
import com.watchwise.watchwise_api.common.tmdb.TmdbProvider;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionProviders;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbVideo;
import com.watchwise.watchwise_api.common.tmdb.TmdbVideos;
import com.watchwise.watchwise_api.common.tmdb.TmdbWatchProviders;
import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentDetailsServiceImplTest {

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TmdbClient tmdbClient;

    private ContentDetailsServiceImpl contentDetailsService;
    private ExecutorService seasonFetchExecutor;

    private UUID requestingUserId;
    private User requestingUser;

    @BeforeEach
    void setUp() {
        seasonFetchExecutor = Executors.newSingleThreadExecutor();
        contentDetailsService = new ContentDetailsServiceImpl(contentRepository, userRepository, tmdbClient, seasonFetchExecutor);
        requestingUserId = UUID.randomUUID();
        requestingUser = User.builder().id(requestingUserId).preferredLanguage("en-US").preferredRegion("US").build();
        lenient().when(userRepository.findById(requestingUserId)).thenReturn(Optional.of(requestingUser));
    }

    @AfterEach
    void tearDown() {
        seasonFetchExecutor.shutdownNow();
    }

    @Test
    @DisplayName("[getDetails] Should Return Movie Details - When Content Is A Movie")
    void shouldReturnMovieDetailsWhenContentIsAMovie() {
        UUID contentId = UUID.randomUUID();
        Content movie = Content.builder().id(contentId).type(ContentType.MOVIE).tmdbId("603").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(movie));
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "The Matrix", "The Matrix", "A hacker discovers reality is a simulation",
                "/poster.jpg", "/backdrop.jpg", "1999-03-31", 136,
                List.of(), List.of(), null, null, null,
                null, null, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.contentId()).isEqualTo(contentId);
        assertThat(result.type()).isEqualTo(ContentType.MOVIE);
        assertThat(result.title()).isEqualTo("The Matrix");
        assertThat(result.releaseDate()).isEqualTo(LocalDate.of(1999, 3, 31));
        assertThat(result.runtimeMinutes()).isEqualTo(136);
    }

    @Test
    @DisplayName("[getDetails] Should Fall Back To Alternative Title For Region - When Translated Title Is Blank")
    void shouldFallBackToAlternativeTitleForRegionWhenTranslatedTitleIsBlank() {
        UUID contentId = UUID.randomUUID();
        Content movie = Content.builder().id(contentId).type(ContentType.MOVIE).tmdbId("603").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(movie));
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "", "Original Title", null, null, null, null, null,
                List.of(), List.of(), null, null,
                new TmdbMovieAlternativeTitles(List.of(
                        new TmdbAlternativeTitleEntry("FR", "Titre Francais"),
                        new TmdbAlternativeTitleEntry("US", "US Release Title"))),
                null, null, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.title()).isEqualTo("US Release Title");
    }

    @Test
    @DisplayName("[getDetails] Should Fall Back To Original Title - When Translated Title Blank And No Alternative Matches Region")
    void shouldFallBackToOriginalTitleWhenTranslatedTitleBlankAndNoAlternativeMatchesRegion() {
        UUID contentId = UUID.randomUUID();
        Content movie = Content.builder().id(contentId).type(ContentType.MOVIE).tmdbId("603").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(movie));
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", null, "Original Title", null, null, null, null, null,
                List.of(), List.of(), null, null,
                new TmdbMovieAlternativeTitles(List.of(new TmdbAlternativeTitleEntry("FR", "Titre Francais"))),
                null, null, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.title()).isEqualTo("Original Title");
    }

    @Test
    @DisplayName("[getDetails] Should Filter Watch Providers By User Region - When Multiple Regions Are Present")
    void shouldFilterWatchProvidersByUserRegionWhenMultipleRegionsArePresent() {
        UUID contentId = UUID.randomUUID();
        Content movie = Content.builder().id(contentId).type(ContentType.MOVIE).tmdbId("603").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(movie));
        TmdbWatchProviders watchProviders = new TmdbWatchProviders(Map.of(
                "US", new TmdbRegionProviders(List.of(new TmdbProvider("Netflix", "/netflix.png")), null, null),
                "BR", new TmdbRegionProviders(List.of(new TmdbProvider("Globoplay", "/globoplay.png")), null, null)));
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "The Matrix", "The Matrix", null, null, null, null, null,
                List.of(), List.of(), null, watchProviders, null,
                null, null, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.watchProviders()).extracting("providerName").containsExactly("Netflix");
    }

    @Test
    @DisplayName("[getDetails] Should Return Empty Watch Providers - When User Region Has No Entry")
    void shouldReturnEmptyWatchProvidersWhenUserRegionHasNoEntry() {
        UUID contentId = UUID.randomUUID();
        Content movie = Content.builder().id(contentId).type(ContentType.MOVIE).tmdbId("603").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(movie));
        TmdbWatchProviders watchProviders = new TmdbWatchProviders(Map.of(
                "BR", new TmdbRegionProviders(List.of(new TmdbProvider("Globoplay", "/globoplay.png")), null, null)));
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "The Matrix", "The Matrix", null, null, null, null, null,
                List.of(), List.of(), null, watchProviders, null,
                null, null, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.watchProviders()).isEmpty();
    }

    @Test
    @DisplayName("[getDetails] Should Return Runtime Totals, Number Of Seasons/Episodes And Aired Episode Count Per Season - When Content Is A Series")
    void shouldReturnRuntimeTotalsNumberOfSeasonsEpisodesAndAiredEpisodeCountPerSeasonWhenContentIsASeries() {
        UUID contentId = UUID.randomUUID();
        Content series = Content.builder().id(contentId).type(ContentType.SERIES).tmdbId("1396").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(series));
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), null,
                List.of(new TmdbSeasonSummary(1, "Season 1", null, "2008-01-20", 2, null),
                        new TmdbSeasonSummary(2, "Season 2", null, "2009-03-08", 2, null),
                        new TmdbSeasonSummary(3, "Season 3", null, "2010-01-01", 1, null)),
                null, null, null, null, 3, 5, null, null)));
        when(tmdbClient.getSeasonFullDetails("1396", 1, "en-US")).thenReturn(Optional.of(new TmdbSeasonFullDetails(
                101, "Season 1", null, null, "2008-01-20", 1, List.of(
                        new TmdbEpisodeSummary(1, "Pilot", null, "2008-01-20", 58, null, null),
                        new TmdbEpisodeSummary(2, "Cat's in the Bag...", null, "2008-01-27", 48, null, null)),
                null, null)));
        when(tmdbClient.getSeasonFullDetails("1396", 2, "en-US")).thenReturn(Optional.of(new TmdbSeasonFullDetails(
                102, "Season 2", null, null, "2009-03-08", 2, List.of(
                        new TmdbEpisodeSummary(1, "Seven Thirty-Seven", null, "2009-03-08", 47, null, null),
                        new TmdbEpisodeSummary(2, "Future Episode", null, "2099-01-01", 45, null, null)),
                null, null)));
        when(tmdbClient.getSeasonFullDetails("1396", 3, "en-US")).thenReturn(Optional.of(new TmdbSeasonFullDetails(
                103, "Season 3", null, null, "2010-01-01", 3, List.of(
                        new TmdbEpisodeSummary(1, "No Mas", null, "2010-01-01", 50, null, null)),
                null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.totalRuntimeMinutes()).isEqualTo(58 + 48 + 47 + 45 + 50);
        assertThat(result.runtimeMinutes()).isEqualTo(Math.round((58 + 48 + 47 + 45 + 50) / 5.0));
        assertThat(result.numberOfSeasons()).isEqualTo(3);
        assertThat(result.numberOfEpisodes()).isEqualTo(5);
        assertThat(result.seasons()).extracting("seasonNumber", "episodeCount", "airedEpisodeCount")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 2, 2),
                        org.assertj.core.groups.Tuple.tuple(2, 2, 1),
                        org.assertj.core.groups.Tuple.tuple(3, 1, 1));
    }

    @Test
    @DisplayName("[getDetails] Should Return Null Aired Episode Count - When That Season's Full Details Failed To Fetch")
    void shouldReturnNullAiredEpisodeCountWhenThatSeasonsFullDetailsFailedToFetch() {
        UUID contentId = UUID.randomUUID();
        Content series = Content.builder().id(contentId).type(ContentType.SERIES).tmdbId("1396").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(series));
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), null,
                List.of(new TmdbSeasonSummary(1, "Season 1", null, "2008-01-20", 7, null)),
                null, null, null, null, null, null, null, null)));
        when(tmdbClient.getSeasonFullDetails("1396", 1, "en-US")).thenReturn(Optional.empty());

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.seasons()).extracting("seasonNumber", "episodeCount", "airedEpisodeCount")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 7, null));
    }

    @Test
    @DisplayName("[getDetails] Should Return Last 3 Already Aired Episodes Sorted By Date Desc - When Content Is A Series")
    void shouldReturnLast3AlreadyAiredEpisodesSortedByDateDescWhenContentIsASeries() {
        UUID contentId = UUID.randomUUID();
        Content series = Content.builder().id(contentId).type(ContentType.SERIES).tmdbId("1396").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(series));
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), null,
                List.of(new TmdbSeasonSummary(1, "Season 1", null, "2008-01-20", 2, null),
                        new TmdbSeasonSummary(2, "Season 2", null, "2009-03-08", 2, null),
                        new TmdbSeasonSummary(3, "Season 3", null, "2010-01-01", 1, null)),
                null, null, null, null, null, null, null, null)));
        when(tmdbClient.getSeasonFullDetails("1396", 1, "en-US")).thenReturn(Optional.of(new TmdbSeasonFullDetails(
                101, "Season 1", null, null, "2008-01-20", 1, List.of(
                        new TmdbEpisodeSummary(1, "Pilot", null, "2008-01-20", 58, null, null),
                        new TmdbEpisodeSummary(2, "Cat's in the Bag...", null, "2008-01-27", 48, null, null)),
                null, null)));
        when(tmdbClient.getSeasonFullDetails("1396", 2, "en-US")).thenReturn(Optional.of(new TmdbSeasonFullDetails(
                102, "Season 2", null, null, "2009-03-08", 2, List.of(
                        new TmdbEpisodeSummary(1, "Seven Thirty-Seven", null, "2009-03-08", 47, null, null),
                        new TmdbEpisodeSummary(2, "Future Episode", null, "2099-01-01", 45, null, null)),
                null, null)));
        when(tmdbClient.getSeasonFullDetails("1396", 3, "en-US")).thenReturn(Optional.of(new TmdbSeasonFullDetails(
                103, "Season 3", null, null, "2010-01-01", 3, List.of(
                        new TmdbEpisodeSummary(1, "No Mas", null, "2010-01-01", 50, null, null)),
                null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.recentEpisodes()).extracting("seasonNumber", "episodeNumber").containsExactly(
                org.assertj.core.groups.Tuple.tuple(3, 1),
                org.assertj.core.groups.Tuple.tuple(2, 1),
                org.assertj.core.groups.Tuple.tuple(1, 2));
    }

    @Test
    @DisplayName("[getDetails] Should Exclude Specials Season Zero From Recent Episodes And Runtime - When Content Is A Series")
    void shouldExcludeSpecialsSeasonZeroFromRecentEpisodesAndRuntimeWhenContentIsASeries() {
        UUID contentId = UUID.randomUUID();
        Content series = Content.builder().id(contentId).type(ContentType.SERIES).tmdbId("1396").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(series));
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), null,
                List.of(new TmdbSeasonSummary(0, "Specials", null, "2099-01-01", 1, null),
                        new TmdbSeasonSummary(1, "Season 1", null, "2008-01-20", 1, null)),
                null, null, null, null, null, null, null, null)));
        when(tmdbClient.getSeasonFullDetails("1396", 1, "en-US")).thenReturn(Optional.of(new TmdbSeasonFullDetails(
                101, "Season 1", null, null, "2008-01-20", 1, List.of(
                        new TmdbEpisodeSummary(1, "Pilot", null, "2008-01-20", 58, null, null)),
                null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.recentEpisodes()).extracting("seasonNumber", "episodeNumber")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 1));
        assertThat(result.totalRuntimeMinutes()).isEqualTo(58);
        assertThat(result.runtimeMinutes()).isEqualTo(58);
        verify(tmdbClient, never()).getSeasonFullDetails(eq("1396"), eq(0), any());
    }

    @Test
    @DisplayName("[getDetails] Should Return Null Total And Average Runtime - When No Episode Has A Known Runtime")
    void shouldReturnNullTotalAndAverageRuntimeWhenNoEpisodeHasAKnownRuntime() {
        UUID contentId = UUID.randomUUID();
        Content series = Content.builder().id(contentId).type(ContentType.SERIES).tmdbId("2316").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(series));
        when(tmdbClient.getTvFullDetails("2316", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "2316", "The Office", "The Office", null, null, null, "2005-03-24", null,
                List.of(), List.of(), null,
                List.of(new TmdbSeasonSummary(1, "Season 1", null, "2005-03-24", 1, null)),
                null, null, null, null, null, null, null, null)));
        when(tmdbClient.getSeasonFullDetails("2316", 1, "en-US")).thenReturn(Optional.of(new TmdbSeasonFullDetails(
                201, "Season 1", null, null, "2005-03-24", 1,
                List.of(new TmdbEpisodeSummary(1, "Pilot", null, "2005-03-24", null, null, null)),
                null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.totalRuntimeMinutes()).isNull();
        assertThat(result.runtimeMinutes()).isNull();
    }

    @Test
    @DisplayName("[getDetails] Should Return Cast From Season-Scoped Aggregate Credits, Not The Series-Wide Ones - When Content Is A Season")
    void shouldReturnCastFromSeasonScopedAggregateCreditsNotTheSeriesWideOnesWhenContentIsASeason() {
        UUID contentId = UUID.randomUUID();
        Content season = Content.builder().id(contentId).type(ContentType.SEASON)
                .seriesTmdbId("1396").seasonNumber(1).build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(season));
        when(tmdbClient.getSeasonFullDetails("1396", 1, "en-US")).thenReturn(Optional.of(new TmdbSeasonFullDetails(
                3572, "Season 1", "First season", "/season1.jpg", "2008-01-20", 1, List.of(
                        new TmdbEpisodeSummary(1, "Pilot", null, "2008-01-20", 58, null, null),
                        new TmdbEpisodeSummary(2, "Cat's in the Bag...", null, "2008-01-27", 48, null, null)),
                new TmdbAggregateCredits(List.of(new TmdbAggregateCastMember(
                        17419, "Bryan Cranston", "/cranston.jpg",
                        List.of(new TmdbAggregateRole("Walter White")), 2)), null),
                null)));
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), null, List.of(), null,
                new TmdbAggregateCredits(List.of(new TmdbAggregateCastMember(
                        17419, "Bryan Cranston", "/cranston.jpg",
                        List.of(new TmdbAggregateRole("Walter White")), 62)), null),
                null, null, null, null, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.type()).isEqualTo(ContentType.SEASON);
        assertThat(result.title()).isEqualTo("Season 1");
        assertThat(result.cast()).extracting("id", "name", "character", "episodeCount")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(17419, "Bryan Cranston", "Walter White", 2));
    }

    @Test
    @DisplayName("[getDetails] Should Return RuntimeMinutes, TotalRuntimeMinutes, NumberOfEpisodes And Creators - When Content Is A Season")
    void shouldReturnRuntimeMinutesTotalRuntimeMinutesNumberOfEpisodesAndCreatorsWhenContentIsASeason() {
        UUID contentId = UUID.randomUUID();
        Content season = Content.builder().id(contentId).type(ContentType.SEASON)
                .seriesTmdbId("1396").seasonNumber(1).build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(season));
        when(tmdbClient.getSeasonFullDetails("1396", 1, "en-US")).thenReturn(Optional.of(new TmdbSeasonFullDetails(
                3572, "Season 1", "First season", "/season1.jpg", "2008-01-20", 1, List.of(
                        new TmdbEpisodeSummary(1, "Pilot", null, "2008-01-20", 58, null, null),
                        new TmdbEpisodeSummary(2, "Cat's in the Bag...", null, "2008-01-27", 48, null, null)),
                null, null)));
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), List.of(new TmdbCreator(9181, "Vince Gilligan", "/gilligan.jpg")),
                List.of(), null, null, null, null, null, null, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.numberOfEpisodes()).isEqualTo(2);
        assertThat(result.totalRuntimeMinutes()).isEqualTo(106);
        assertThat(result.runtimeMinutes()).isEqualTo(53);
        assertThat(result.creators()).extracting("name", "profilePath")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Vince Gilligan", "/gilligan.jpg"));
    }

    @Test
    @DisplayName("[getDetails] Should Aggregate Guest Stars Across Episodes With Per-Season Episode Count - When Content Is A Season")
    void shouldAggregateGuestStarsAcrossEpisodesWithPerSeasonEpisodeCountWhenContentIsASeason() {
        UUID contentId = UUID.randomUUID();
        Content season = Content.builder().id(contentId).type(ContentType.SEASON)
                .seriesTmdbId("1396").seasonNumber(1).build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(season));
        when(tmdbClient.getSeasonFullDetails("1396", 1, "en-US")).thenReturn(Optional.of(new TmdbSeasonFullDetails(
                3572, "Season 1", "First season", "/season1.jpg", "2008-01-20", 1, List.of(
                        new TmdbEpisodeSummary(1, "Pilot", null, "2008-01-20", 58, null,
                                List.of(new TmdbGuestStar(500, "Guest One", "Neighbor", "/guest1.jpg"))),
                        new TmdbEpisodeSummary(2, "Cat's in the Bag...", null, "2008-01-27", 48, null,
                                List.of(new TmdbGuestStar(500, "Guest One", "Neighbor", "/guest1.jpg"),
                                        new TmdbGuestStar(501, "Guest Two", "Cop", "/guest2.jpg")))),
                null, null)));
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), null, List.of(), null, null, null, null, null, null, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.guestStars()).extracting("id", "name", "character", "episodeCount")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(500, "Guest One", "Neighbor", 2),
                        org.assertj.core.groups.Tuple.tuple(501, "Guest Two", "Cop", 1));
    }

    @Test
    @DisplayName("[getDetails] Should Parse Guest Stars And Reuse Series Cast - When Content Is An Episode")
    void shouldParseGuestStarsAndReuseSeriesCastWhenContentIsAnEpisode() {
        UUID contentId = UUID.randomUUID();
        Content episode = Content.builder().id(contentId).type(ContentType.EPISODE)
                .seriesTmdbId("1396").seasonNumber(1).episodeNumber(1).build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(episode));
        when(tmdbClient.getEpisodeFullDetails("1396", 1, 1, "en-US")).thenReturn(Optional.of(new TmdbEpisodeFullDetails(
                62085, "Pilot", "First episode", "2008-01-20", 1, 1, 58, "/still.jpg",
                List.of(new TmdbGuestStar(17420, "John Doe", "Neighbor", "/doe.jpg")))));
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), null, List.of(), null, null, null, null, null, null, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.type()).isEqualTo(ContentType.EPISODE);
        assertThat(result.title()).isEqualTo("Pilot");
        assertThat(result.guestStars()).extracting("name").containsExactly("John Doe");
        assertThat(result.watchProviders()).isEmpty();
    }

    @Test
    @DisplayName("[getDetails] Should Return Budget Revenue Production Companies Crew And Videos - When Content Is A Movie")
    void shouldReturnBudgetRevenueProductionCompaniesCrewAndVideosWhenContentIsAMovie() {
        UUID contentId = UUID.randomUUID();
        Content movie = Content.builder().id(contentId).type(ContentType.MOVIE).tmdbId("603").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(movie));
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "The Matrix", "The Matrix", null, null, null, null, null, List.of(), List.of(),
                new com.watchwise.watchwise_api.common.tmdb.TmdbCredits(List.of(), List.of(
                        new TmdbCrewMember(10, "Lana Wachowski", "Director", "/lana.jpg"),
                        new TmdbCrewMember(11, "Best Boy Grip", "Best Boy Grip", null))),
                null, null,
                63000000L, 463500000L,
                List.of(new TmdbProductionCompany(79, "Village Roadshow Pictures", "/village.png", "US")),
                new TmdbVideos(List.of(new TmdbVideo("vKQi3bBA1y8", "Trailer", "YouTube", "Trailer", true, "en",
                        "1999-03-01T00:00:00.000Z"))))));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.budget()).isEqualTo(63000000L);
        assertThat(result.revenue()).isEqualTo(463500000L);
        assertThat(result.productionCompanies()).extracting("id", "name", "logoPath", "originCountry")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(79, "Village Roadshow Pictures", "/village.png", "US"));
        assertThat(result.crew()).extracting("id", "name", "profilePath", "jobs")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(10, "Lana Wachowski", "/lana.jpg", List.of("Director")));
        assertThat(result.videos()).extracting("key", "name", "site", "type", "official", "language", "publishedAt")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "vKQi3bBA1y8", "Trailer", "YouTube", "Trailer", true, "en", Instant.parse("1999-03-01T00:00:00.000Z")));
    }

    @Test
    @DisplayName("[getDetails] Should Return Null Budget And Revenue - When TMDB Reports Them As Zero")
    void shouldReturnNullBudgetAndRevenueWhenTmdbReportsThemAsZero() {
        UUID contentId = UUID.randomUUID();
        Content movie = Content.builder().id(contentId).type(ContentType.MOVIE).tmdbId("603").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(movie));
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "The Matrix", "The Matrix", null, null, null, null, null, List.of(), List.of(), null, null, null,
                0L, 0L, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.budget()).isNull();
        assertThat(result.revenue()).isNull();
    }

    @Test
    @DisplayName("[getDetails] Should Group Multiple Matching Jobs For The Same Crew Member - When Content Is A Movie")
    void shouldGroupMultipleMatchingJobsForTheSameCrewMemberWhenContentIsAMovie() {
        UUID contentId = UUID.randomUUID();
        Content movie = Content.builder().id(contentId).type(ContentType.MOVIE).tmdbId("603").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(movie));
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "The Matrix", "The Matrix", null, null, null, null, null, List.of(), List.of(),
                new com.watchwise.watchwise_api.common.tmdb.TmdbCredits(List.of(), List.of(
                        new TmdbCrewMember(10, "Lana Wachowski", "Director", "/lana.jpg"),
                        new TmdbCrewMember(10, "Lana Wachowski", "Screenplay", "/lana.jpg"))),
                null, null, null, null, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.crew()).extracting("id", "jobs")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(10, List.of("Director", "Screenplay")));
    }

    @Test
    @DisplayName("[getDetails] Should Silently Exclude Crew Member With Null Job - When Content Is A Movie")
    void shouldSilentlyExcludeCrewMemberWithNullJobWhenContentIsAMovie() {
        UUID contentId = UUID.randomUUID();
        Content movie = Content.builder().id(contentId).type(ContentType.MOVIE).tmdbId("603").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(movie));
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "The Matrix", "The Matrix", null, null, null, null, null, List.of(), List.of(),
                new com.watchwise.watchwise_api.common.tmdb.TmdbCredits(List.of(), List.of(
                        new TmdbCrewMember(10, "Lana Wachowski", "Director", "/lana.jpg"),
                        new TmdbCrewMember(20, "Unknown Role", null, "/unknown.jpg"))),
                null, null, null, null, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.crew()).extracting("id", "jobs")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(10, List.of("Director")));
    }

    @Test
    @DisplayName("[getDetails] Should Return Production Companies Aggregate Crew And Videos But Null Budget And Revenue - When Content Is A Series")
    void shouldReturnProductionCompaniesAggregateCrewAndVideosButNullBudgetAndRevenueWhenContentIsASeries() {
        UUID contentId = UUID.randomUUID();
        Content series = Content.builder().id(contentId).type(ContentType.SERIES).tmdbId("1396").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(series));
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), null, List.of(), null,
                new TmdbAggregateCredits(List.of(), List.of(
                        new TmdbAggregateCrewMember(66633, "Vince Gilligan", "/vince.jpg",
                                List.of(new TmdbAggregateCrewJob("Director"), new TmdbAggregateCrewJob("Executive Producer"))),
                        new TmdbAggregateCrewMember(99999, "Random Grip", null, List.of(new TmdbAggregateCrewJob("Grip"))))),
                null, null, null, null,
                List.of(new TmdbProductionCompany(11073, "Sony Pictures Television", "/sony.png", "US")),
                new TmdbVideos(List.of(new TmdbVideo("abc123", "Official Trailer", "YouTube", "Trailer", true, "en",
                        "2008-01-01T00:00:00.000Z"))))));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.budget()).isNull();
        assertThat(result.revenue()).isNull();
        assertThat(result.productionCompanies()).extracting("id", "name")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(11073, "Sony Pictures Television"));
        assertThat(result.crew()).extracting("id", "jobs")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(66633, List.of("Director", "Executive Producer")));
        assertThat(result.videos()).extracting("key", "name").containsExactly(org.assertj.core.groups.Tuple.tuple("abc123", "Official Trailer"));
    }

    @Test
    @DisplayName("[getDetails] Should Filter Out Null Job And Keep Only Matching Jobs For Aggregate Crew Member - When Content Is A Series")
    void shouldFilterOutNullJobAndKeepOnlyMatchingJobsForAggregateCrewMemberWhenContentIsASeries() {
        UUID contentId = UUID.randomUUID();
        Content series = Content.builder().id(contentId).type(ContentType.SERIES).tmdbId("1396").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(series));
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), null, List.of(), null,
                new TmdbAggregateCredits(List.of(), List.of(
                        new TmdbAggregateCrewMember(66633, "Vince Gilligan", "/vince.jpg",
                                List.of(new TmdbAggregateCrewJob(null), new TmdbAggregateCrewJob("Director"))))),
                null, null, null, null, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.crew()).extracting("id", "jobs")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(66633, List.of("Director")));
    }

    @Test
    @DisplayName("[getDetails] Should Throw NotFoundException - When Content Does Not Exist")
    void shouldThrowNotFoundExceptionWhenContentDoesNotExist() {
        UUID contentId = UUID.randomUUID();
        when(contentRepository.findById(contentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> contentDetailsService.getDetails(contentId, requestingUserId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Content not found");
    }

    @Test
    @DisplayName("[getDetails] Should Throw NotFoundException - When Requesting User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenRequestingUserDoesNotExist() {
        UUID contentId = UUID.randomUUID();
        UUID unknownUserId = UUID.randomUUID();
        when(userRepository.findById(unknownUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> contentDetailsService.getDetails(contentId, unknownUserId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    @DisplayName("[getDetails] Should Throw TmdbUnavailableException - When TMDB Fails")
    void shouldThrowTmdbUnavailableExceptionWhenTmdbFails() {
        UUID contentId = UUID.randomUUID();
        Content movie = Content.builder().id(contentId).type(ContentType.MOVIE).tmdbId("603").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(movie));
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> contentDetailsService.getDetails(contentId, requestingUserId))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    @DisplayName("[getDetailsBatch] Should Return One Entry Per Id In The Same Order - When Called With Multiple Ids")
    void shouldReturnOneEntryPerIdInTheSameOrderWhenCalledWithMultipleIds() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Content firstContent = Content.builder().id(first).type(ContentType.MOVIE).tmdbId("603").build();
        Content secondContent = Content.builder().id(second).type(ContentType.MOVIE).tmdbId("604").build();
        when(contentRepository.findById(first)).thenReturn(Optional.of(firstContent));
        when(contentRepository.findById(second)).thenReturn(Optional.of(secondContent));
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "First", "First", null, null, null, null, null, List.of(), List.of(), null, null, null,
                null, null, null, null)));
        when(tmdbClient.getMovieFullDetails("604", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "604", "Second", "Second", null, null, null, null, null, List.of(), List.of(), null, null, null,
                null, null, null, null)));

        List<ContentDetailsDTO> result = contentDetailsService.getDetailsBatch(List.of(first, second), requestingUserId);

        assertThat(result).extracting(ContentDetailsDTO::contentId).containsExactly(first, second);
        assertThat(result).extracting(ContentDetailsDTO::title).containsExactly("First", "Second");
    }

    @Test
    @DisplayName("[getDetailsBatch] Should Throw BadRequestException - When Ids Is Empty")
    void shouldThrowBadRequestExceptionWhenIdsIsEmpty() {
        assertThatThrownBy(() -> contentDetailsService.getDetailsBatch(List.of(), requestingUserId))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("ids must not be empty");
    }

    @Test
    @DisplayName("[getDetailsBatch] Should Throw BadRequestException - When Ids Exceed The Batch Limit")
    void shouldThrowBadRequestExceptionWhenIdsExceedTheBatchLimit() {
        List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID)
                .limit(ContentDetailsServiceImpl.MAX_BATCH_IDS + 1)
                .toList();

        assertThatThrownBy(() -> contentDetailsService.getDetailsBatch(tooMany, requestingUserId))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Cannot request details for more than " + ContentDetailsServiceImpl.MAX_BATCH_IDS + " contents at once");
    }
}
