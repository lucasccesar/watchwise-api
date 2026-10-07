package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCredits;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCrewJob;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCrewMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbExternalIds;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbProvider;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionProviders;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvContentRating;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvContentRatings;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbWatchProviders;
import com.watchwise.watchwise_api.content.dto.ContentPageMetadataDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentPageMetadataServiceImplTest {

    @Mock
    private TmdbClient tmdbClient;

    private ContentPageMetadataServiceImpl metadataService;

    @BeforeEach
    void setUp() {
        metadataService = new ContentPageMetadataServiceImpl(tmdbClient);
    }

    @Test
    @DisplayName("[getMetadata] Should Map Movie Metadata And Providers - When TMDB Returns Regional Data")
    void shouldMapMovieMetadataAndProvidersWhenTmdbReturnsRegionalData() {
        Content content = Content.builder().tmdbId("550").type(ContentType.MOVIE).build();
        when(tmdbClient.getMovieFullDetails("550", "pt-BR"))
                .thenReturn(found(movieDetails("en", "https://fight.club", "tt0137523", watchProviders("BR"))));
        when(tmdbClient.getMovieReleaseDates("550", "pt-BR"))
                .thenReturn(found(new TmdbMovieReleaseDates("550", List.of(
                        new TmdbRegionReleaseDates("BR", List.of(
                                new TmdbMovieReleaseDate("18", "pt", "1999-10-29", null, 3)))))));

        ContentPageMetadataDTO result = metadataService.getMetadata(content, "pt-BR", "BR");

        assertThat(result.originalLanguage()).isEqualTo("en");
        assertThat(result.certification()).isEqualTo("18");
        assertThat(result.homepageUrl()).isEqualTo("https://fight.club");
        assertThat(result.tmdbUrl()).isEqualTo("https://www.themoviedb.org/movie/550");
        assertThat(result.imdbUrl()).isEqualTo("https://www.imdb.com/title/tt0137523");
        assertThat(result.watchProviders()).extracting(
                        provider -> provider.providerId(),
                        provider -> provider.providerName(),
                        provider -> provider.type(),
                        provider -> provider.watchUrl())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(8, "Netflix", "flatrate", "https://justwatch.com/br/filme/fight-club"),
                        org.assertj.core.groups.Tuple.tuple(179, "Google Play", "rent", "https://justwatch.com/br/filme/fight-club"));
    }

    @Test
    @DisplayName("[getMetadata] Should Map Tv Certification And Links - When TV Ratings And Full Details Exist")
    void shouldMapTvCertificationAndLinksWhenTvRatingsAndFullDetailsExist() {
        Content content = Content.builder().tmdbId("1396").type(ContentType.SERIES).build();
        when(tmdbClient.getTvFullDetails("1396", "en-US"))
                .thenReturn(found(tvDetails("es", "https://breakingbad.example", "tt0903747")));
        when(tmdbClient.getTvContentRatings("1396", "en-US"))
                .thenReturn(found(new TmdbTvContentRatings("1396", List.of(
                        new TmdbTvContentRating("US", "TV-MA")))));

        ContentPageMetadataDTO result = metadataService.getMetadata(content, "en-US", "US");

        assertThat(result.originalLanguage()).isEqualTo("es");
        assertThat(result.certification()).isEqualTo("TV-MA");
        assertThat(result.homepageUrl()).isEqualTo("https://breakingbad.example");
        assertThat(result.tmdbUrl()).isEqualTo("https://www.themoviedb.org/tv/1396");
        assertThat(result.imdbUrl()).isEqualTo("https://www.imdb.com/title/tt0903747");
    }

    @Test
    @DisplayName("[getMetadata] Should Inherit Season Metadata And Use Season Links - When Content Is A Season")
    void shouldInheritSeasonMetadataAndUseSeasonLinksWhenContentIsASeason() {
        Content content = Content.builder()
                .type(ContentType.SEASON)
                .seriesTmdbId("1396")
                .seasonNumber(1)
                .build();
        when(tmdbClient.getSeasonFullDetails("1396", 1, "en-US"))
                .thenReturn(found(new TmdbSeasonFullDetails(
                        3572, "Season 1", null, null, "2008-01-20", 1, List.of(), null,
                        watchProviders("US"))));
        when(tmdbClient.getTvFullDetails("1396", "en-US"))
                .thenReturn(found(tvDetails("en", "https://breakingbad.example", "tt0903747")));
        when(tmdbClient.getTvContentRatings("1396", "en-US"))
                .thenReturn(found(new TmdbTvContentRatings("1396", List.of(
                        new TmdbTvContentRating("US", "TV-MA")))));

        ContentPageMetadataDTO result = metadataService.getMetadata(content, "en-US", "US");

        assertThat(result.originalLanguage()).isEqualTo("en");
        assertThat(result.certification()).isEqualTo("TV-MA");
        assertThat(result.homepageUrl()).isEqualTo("https://breakingbad.example");
        assertThat(result.tmdbUrl()).isEqualTo("https://www.themoviedb.org/tv/1396/season/1");
        assertThat(result.imdbUrl()).isEqualTo("https://www.imdb.com/title/tt0903747");
        assertThat(result.watchProviders()).extracting(provider -> provider.providerId())
                .containsExactly(8, 179);
    }

    @Test
    @DisplayName("[getMetadata] Should Inherit Episode Metadata And Use Episode Links - When Content Is An Episode")
    void shouldInheritEpisodeMetadataAndUseEpisodeLinksWhenContentIsAnEpisode() {
        Content content = Content.builder()
                .type(ContentType.EPISODE)
                .seriesTmdbId("1396")
                .seasonNumber(1)
                .episodeNumber(2)
                .build();
        when(tmdbClient.getEpisodeFullDetails("1396", 1, 2, "en-US"))
                .thenReturn(found(new TmdbEpisodeFullDetails(62086, "Cat's in the Bag...", null,
                        "2008-02-17", 2, 1, 47, "/episode-still.jpg", List.of(),
                        new TmdbExternalIds("tt1234567", null, null, null))));
        when(tmdbClient.getTvFullDetails("1396", "en-US"))
                .thenReturn(found(tvDetails("en", "https://breakingbad.example", "tt0903747")));
        when(tmdbClient.getTvContentRatings("1396", "en-US"))
                .thenReturn(found(new TmdbTvContentRatings("1396", List.of(
                        new TmdbTvContentRating("US", "TV-MA")))));

        ContentPageMetadataDTO result = metadataService.getMetadata(content, "en-US", "US");

        assertThat(result.originalLanguage()).isEqualTo("en");
        assertThat(result.certification()).isEqualTo("TV-MA");
        assertThat(result.homepageUrl()).isEqualTo("https://breakingbad.example");
        assertThat(result.tmdbUrl()).isEqualTo("https://www.themoviedb.org/tv/1396/season/1/episode/2");
        assertThat(result.imdbUrl()).isEqualTo("https://www.imdb.com/title/tt1234567");
        assertThat(result.watchProviders()).extracting(provider -> provider.providerName())
                .containsExactly("Netflix", "Google Play");
        assertThat(result.presentationPosterPath()).isEqualTo("/episode-still.jpg");
        assertThat(result.presentationCrew()).extracting(crew -> crew.name())
                .containsExactly("Vince Gilligan");
        assertThat(result.crewInherited()).isTrue();
    }

    @Test
    @DisplayName("[getMetadata] Should Keep Episode IMDb URL Null - When Episode Has No Own IMDb ID")
    void shouldKeepEpisodeImdbUrlNullWhenEpisodeHasNoOwnImdbId() {
        Content content = Content.builder()
                .type(ContentType.EPISODE)
                .seriesTmdbId("1396")
                .seasonNumber(1)
                .episodeNumber(2)
                .build();
        when(tmdbClient.getEpisodeFullDetails("1396", 1, 2, "en-US"))
                .thenReturn(found(new TmdbEpisodeFullDetails(62086, "Cat's in the Bag...", null,
                        "2008-02-17", 2, 1, 47, null, List.of(), null)));
        when(tmdbClient.getTvFullDetails("1396", "en-US"))
                .thenReturn(found(tvDetails("en", "https://breakingbad.example", "tt0903747")));
        when(tmdbClient.getTvContentRatings("1396", "en-US"))
                .thenReturn(found(new TmdbTvContentRatings("1396", List.of(
                        new TmdbTvContentRating("US", "TV-MA")))));

        ContentPageMetadataDTO result = metadataService.getMetadata(content, "en-US", "US");

        assertThat(result.imdbUrl()).isNull();
    }

    @Test
    @DisplayName("[getMetadata] Should Return Nullable Optional Fields And No Wikipedia Link - When TMDB Omits Them")
    void shouldReturnNullableOptionalFieldsAndNoWikipediaLinkWhenTmdbOmitsThem() {
        Content content = Content.builder().tmdbId("550").type(ContentType.MOVIE).build();
        when(tmdbClient.getMovieFullDetails("550", "en-US"))
                .thenReturn(found(movieDetails("en", null, null, null)));
        when(tmdbClient.getMovieReleaseDates("550", "en-US"))
                .thenReturn(found(new TmdbMovieReleaseDates("550", null)));

        ContentPageMetadataDTO result = metadataService.getMetadata(content, "en-US", "US");

        assertThat(result.certification()).isNull();
        assertThat(result.homepageUrl()).isNull();
        assertThat(result.imdbUrl()).isNull();
        assertThat(result.watchProviders()).isEmpty();
    }

    private TmdbMovieFullDetails movieDetails(
            String language, String homepage, String imdbId, TmdbWatchProviders watchProviders) {
        return new TmdbMovieFullDetails(
                "550", "Fight Club", "Fight Club", language, "A story", null, null,
                "1999-10-15", 139, List.of(), List.of(), null, watchProviders, null,
                null, null, null, null, "Released", imdbId == null ? null : new TmdbExternalIds(imdbId, null, null, null),
                null, homepage);
    }

    private TmdbTvFullDetails tvDetails(String language, String homepage, String imdbId) {
        return new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", language, "A story", null, null,
                "2008-01-20", List.of(47), List.of(), List.of(), List.of(), List.of(), null,
                new TmdbAggregateCredits(
                        List.of(),
                        List.of(new TmdbAggregateCrewMember(
                                42, "Vince Gilligan", "/vince.jpg",
                                List.of(new TmdbAggregateCrewJob("Executive Producer"))))),
                watchProviders("US"), null, 5, 62, List.of(), null, "Ended",
                imdbId == null ? null : new TmdbExternalIds(imdbId, null, null, null), List.of(), homepage);
    }

    private TmdbWatchProviders watchProviders(String region) {
        return new TmdbWatchProviders(Map.of(region, new TmdbRegionProviders(
                List.of(new TmdbProvider(8, "Netflix", "/netflix.png")),
                List.of(new TmdbProvider(179, "Google Play", "/google.png")),
                null,
                "https://justwatch.com/br/filme/fight-club")));
    }

    private <T> TmdbLookupResult<T> found(T value) {
        return new TmdbLookupResult.Found<>(value);
    }
}
