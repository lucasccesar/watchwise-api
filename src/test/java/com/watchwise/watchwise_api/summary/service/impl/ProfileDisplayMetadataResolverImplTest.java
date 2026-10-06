package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileDisplayMetadataResolverImplTest {

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private UserContentPosterService userContentPosterService;

    @Test
    void shouldResolveExternalSeriesAndApplyOwnerPosterOverride() {
        UUID ownerId = UUID.randomUUID();
        TmdbTvFullDetails details = mock(TmdbTvFullDetails.class);
        when(details.name()).thenReturn("Severance");
        when(details.firstAirDate()).thenReturn("2022-02-18");
        when(details.posterPath()).thenReturn("/tmdb-poster.jpg");
        when(tmdbClient.getTvFullDetails("95396", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(details));
        when(userContentPosterService.findSeriesPosters(ownerId, List.of("95396")))
                .thenReturn(Map.of("95396", "https://watchwise/custom.jpg"));

        ProfileDisplayMetadataResolverImpl resolver = new ProfileDisplayMetadataResolverImpl(
                tmdbClient, userContentPosterService);

        var result = resolver.resolveSeries(ownerId, "95396");

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo(ContentType.SERIES);
        assertThat(result.seriesTmdbId()).isEqualTo("95396");
        assertThat(result.title()).isEqualTo("Severance");
        assertThat(result.releaseYear()).isEqualTo(2022);
        assertThat(result.customPosterUrl()).isEqualTo("https://watchwise/custom.jpg");
    }

    @Test
    void shouldOmitStoredHighlightWhenTmdbIsUnavailable() {
        UUID ownerId = UUID.randomUUID();
        Content movie = Content.builder().id(UUID.randomUUID()).tmdbId("550").type(ContentType.MOVIE).build();
        when(userContentPosterService.findByUserAndContentIds(ownerId, List.of(movie.getId())))
                .thenReturn(Map.of());
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        ProfileDisplayMetadataResolverImpl resolver = new ProfileDisplayMetadataResolverImpl(
                tmdbClient, userContentPosterService);

        assertThat(resolver.resolveStoredContent(ownerId, movie)).isNull();
    }
}
