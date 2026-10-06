package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbCacheConfig;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDisplayDetails;
import com.watchwise.watchwise_api.summary.service.EpisodeRatingsMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpisodeRatingsMetadataServiceImplTest {

    @Mock
    private TmdbClient tmdbClient;

    private ExecutorService metadataExecutor;
    private EpisodeRatingsMetadataServiceImpl service;

    @BeforeEach
    void setUp() {
        metadataExecutor = new TmdbCacheConfig().episodeRatingsMetadataExecutor();
        service = new EpisodeRatingsMetadataServiceImpl(tmdbClient, metadataExecutor);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        metadataExecutor.shutdownNow();
        assertThat(metadataExecutor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldResolveBatchConcurrentlyWithoutExceedingFourActiveLookups() throws Exception {
        CountDownLatch fourStarted = new CountDownLatch(4);
        CountDownLatch releaseLookups = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peakActive = new AtomicInteger();
        when(tmdbClient.getTvDisplayDetails(anyString(), eq("pt-BR"))).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            peakActive.accumulateAndGet(current, Math::max);
            fourStarted.countDown();
            try {
                if (!releaseLookups.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release TMDB lookups");
                }
                String id = invocation.getArgument(0);
                return new TmdbLookupResult.Found<>(
                        new TmdbTvDisplayDetails(id, "Series " + id, null, null, null));
            } finally {
                active.decrementAndGet();
            }
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, EpisodeRatingsMetadata>> result = caller.submit(() -> service.resolve(
                    List.of("1", "2", "3", "4", "5", "6"), "pt-BR"));

            assertThat(fourStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(peakActive.get()).isEqualTo(4);
            releaseLookups.countDown();
            assertThat(result.get(3, TimeUnit.SECONDS).keySet())
                    .containsExactly("1", "2", "3", "4", "5", "6");
            assertThat(peakActive.get()).isEqualTo(4);
        } finally {
            releaseLookups.countDown();
            caller.shutdownNow();
            assertThat(caller.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void shouldMapLocalizedDisplayDetailsAndBuildImageUrls() {
        when(tmdbClient.getTvDisplayDetails("1396", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbTvDisplayDetails(
                        "1396", "Breaking Bad", "2008-01-20", "/poster.jpg", "/backdrop.jpg",
                        List.of(new com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary(0, null, null, null, null, null),
                                new com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary(1, null, null, null, null, null),
                                new com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary(3, null, null, null, null, null)))));

        var metadata = service.resolve(" 1396 ", "pt-BR");

        assertThat(metadata).isEqualTo(new EpisodeRatingsMetadata(
                "1396", "Breaking Bad", 2008,
                "https://image.tmdb.org/t/p/w500/poster.jpg",
                "https://image.tmdb.org/t/p/w1280/backdrop.jpg", List.of(1, 3)));
        verify(tmdbClient).getTvDisplayDetails("1396", "pt-BR");
    }

    @Test
    void shouldFailWhenDisplayDetailsOmitSeasonList() {
        when(tmdbClient.getTvDisplayDetails("1396", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbTvDisplayDetails(
                        "1396", "Breaking Bad", "2008-01-20", null, null, null)));

        assertThatThrownBy(() -> service.resolve("1396", "pt-BR"))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    void shouldReturnNullReleaseYearWhenFirstAirDateIsMissing() {
        when(tmdbClient.getTvDisplayDetails("1396", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbTvDisplayDetails(
                        "1396", "Breaking Bad", null, null, null)));

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
        assertThatThrownBy(() -> service.resolve("  ", "pt-BR"))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(tmdbClient);
    }

    @Test
    void shouldRejectAnyBlankIdInBatchBeforeCallingTmdb() {
        assertThatThrownBy(() -> service.resolve(List.of("1396", " "), "pt-BR"))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(tmdbClient);
    }

    @Test
    void shouldThrowNotFoundWhenTmdbDoesNotKnowTheSeries() {
        when(tmdbClient.getTvDisplayDetails("999999999", "pt-BR"))
                .thenReturn(new TmdbLookupResult.NotFound<>());

        assertThatThrownBy(() -> service.resolve("999999999", "pt-BR"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void shouldThrowTmdbUnavailableWhenTmdbCannotResolveTheSeries() {
        when(tmdbClient.getTvDisplayDetails("1396", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.resolve("1396", "pt-BR"))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    void shouldPreserveNotFoundWhenResolvingBatch() {
        when(tmdbClient.getTvDisplayDetails("1396", "pt-BR"))
                .thenReturn(new TmdbLookupResult.NotFound<>());

        assertThatThrownBy(() -> service.resolve(List.of("1396"), "pt-BR"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Series not found in TMDB");
    }

    @Test
    void shouldPreserveTmdbUnavailableWhenResolvingBatch() {
        when(tmdbClient.getTvDisplayDetails("1396", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.resolve(List.of("1396"), "pt-BR"))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB is currently unavailable");
    }
}
