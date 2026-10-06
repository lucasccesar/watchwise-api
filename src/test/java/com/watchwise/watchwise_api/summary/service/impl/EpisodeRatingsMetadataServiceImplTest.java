package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDisplayDetails;
import com.watchwise.watchwise_api.summary.service.EpisodeRatingsMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpisodeRatingsMetadataServiceImplTest {

    @Mock
    private TmdbClient tmdbClient;

    @Test
    void shouldMapLocalizedDisplayDetailsAndBuildImageUrls() {
        when(tmdbClient.getTvDisplayDetails("1396", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbTvDisplayDetails(
                        "1396", "Breaking Bad", "2008-01-20", "/poster.jpg", "/backdrop.jpg")));
        EpisodeRatingsMetadataServiceImpl service = new EpisodeRatingsMetadataServiceImpl(tmdbClient);

        var metadata = service.resolve(" 1396 ", "pt-BR");

        assertThat(metadata).isEqualTo(new EpisodeRatingsMetadata(
                "1396", "Breaking Bad", 2008,
                "https://image.tmdb.org/t/p/w500/poster.jpg",
                "https://image.tmdb.org/t/p/w1280/backdrop.jpg"));
        verify(tmdbClient).getTvDisplayDetails("1396", "pt-BR");
    }

    @Test
    void shouldReturnNullReleaseYearWhenFirstAirDateIsMissing() {
        when(tmdbClient.getTvDisplayDetails("1396", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbTvDisplayDetails(
                        "1396", "Breaking Bad", null, null, null)));
        EpisodeRatingsMetadataServiceImpl service = new EpisodeRatingsMetadataServiceImpl(tmdbClient);

        var metadata = service.resolve("1396", "pt-BR");

        assertThat(metadata.releaseYear()).isNull();
        assertThat(metadata.posterPath()).isNull();
        assertThat(metadata.backdropPath()).isNull();
    }

    @Test
    void shouldNormalizeAndDeduplicateIdsWhenResolvingManySeries() {
        when(tmdbClient.getTvDisplayDetails("1396", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbTvDisplayDetails(
                        "1396", "Breaking Bad", "2008-01-20", null, null)));
        when(tmdbClient.getTvDisplayDetails("95396", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbTvDisplayDetails(
                        "95396", "Severance", "2022-02-18", null, null)));
        EpisodeRatingsMetadataServiceImpl service = new EpisodeRatingsMetadataServiceImpl(tmdbClient);

        Map<String, EpisodeRatingsMetadata> metadata = service.resolve(
                List.of(" 1396", "95396 ", "1396 "), "pt-BR");

        assertThat(metadata).containsOnlyKeys("1396", "95396");
        assertThat(metadata.get("1396").title()).isEqualTo("Breaking Bad");
        assertThat(metadata.get("95396").releaseYear()).isEqualTo(2022);
        verify(tmdbClient).getTvDisplayDetails("1396", "pt-BR");
        verify(tmdbClient).getTvDisplayDetails("95396", "pt-BR");
    }

    @Test
    void shouldRejectBlankSeriesIdBeforeCallingTmdb() {
        EpisodeRatingsMetadataServiceImpl service = new EpisodeRatingsMetadataServiceImpl(tmdbClient);

        assertThatThrownBy(() -> service.resolve("  ", "pt-BR"))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(tmdbClient);
    }

    @Test
    void shouldRejectAnyBlankIdInBatchBeforeCallingTmdb() {
        EpisodeRatingsMetadataServiceImpl service = new EpisodeRatingsMetadataServiceImpl(tmdbClient);

        assertThatThrownBy(() -> service.resolve(List.of("1396", " "), "pt-BR"))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(tmdbClient);
    }

    @Test
    void shouldThrowNotFoundWhenTmdbDoesNotKnowTheSeries() {
        when(tmdbClient.getTvDisplayDetails("999999999", "pt-BR"))
                .thenReturn(new TmdbLookupResult.NotFound<>());
        EpisodeRatingsMetadataServiceImpl service = new EpisodeRatingsMetadataServiceImpl(tmdbClient);

        assertThatThrownBy(() -> service.resolve("999999999", "pt-BR"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void shouldThrowTmdbUnavailableWhenTmdbCannotResolveTheSeries() {
        when(tmdbClient.getTvDisplayDetails("1396", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Unavailable<>());
        EpisodeRatingsMetadataServiceImpl service = new EpisodeRatingsMetadataServiceImpl(tmdbClient);

        assertThatThrownBy(() -> service.resolve("1396", "pt-BR"))
                .isInstanceOf(TmdbUnavailableException.class);
    }
}
