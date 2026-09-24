package com.watchwise.watchwise_api.seriesprogress.service.impl;

import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeCalculator;
import com.watchwise.watchwise_api.seriesprogress.entity.SeriesProgressMetadata;
import com.watchwise.watchwise_api.seriesprogress.entity.SeriesProgressSeasonMetadata;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressMetadataRepository;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressSeasonMetadataRepository;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataCalculator;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataRefreshService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeriesProgressMetadataRefreshServiceImplTest {

    private static final String SERIES_ID = "1399";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);

    @Mock private TmdbClient tmdbClient;
    @Mock private SeriesProgressMetadataRepository metadataRepository;
    @Mock private SeriesProgressSeasonMetadataRepository seasonMetadataRepository;
    @Mock private NewTransactionExecutor newTransactionExecutor;

    private ExecutorService seasonExecutor;
    private SeriesProgressMetadataRefreshServiceImpl service;

    @BeforeEach
    void setUp() {
        seasonExecutor = Executors.newFixedThreadPool(4);
        lenient().when(newTransactionExecutor.runInNewTransaction(any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        service = new SeriesProgressMetadataRefreshServiceImpl(
                tmdbClient,
                metadataRepository,
                seasonMetadataRepository,
                new SeriesProgressMetadataCalculator(new SeriesRuntimeCalculator()),
                seasonExecutor,
                newTransactionExecutor);
        lenient().when(metadataRepository.findById(SERIES_ID)).thenReturn(Optional.empty());
        lenient().when(seasonMetadataRepository.findAllBySeriesTmdbIdIn(anyCollection())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        seasonExecutor.shutdownNow();
    }

    @Test
    @DisplayName("[refresh] Loads TV Once And Each Positive Season Once")
    void shouldLoadTvOnceAndEachPositiveSeasonOnce() {
        TmdbTvFullDetails details = tv(
                summary(0), summary(2), summary(1), summary(2));
        when(tmdbClient.getSeasonFullDetails(SERIES_ID, 1, "en-US"))
                .thenReturn(found(season(1, episode(1, "2026-09-01", 40))));
        when(tmdbClient.getSeasonFullDetails(SERIES_ID, 2, "en-US"))
                .thenReturn(found(season(2, episode(1, "2026-09-02", 50))));

        SeriesProgressMetadataRefreshService.Snapshot result = service.refresh(SERIES_ID, details, TODAY);

        assertThat(result.series().regularReleasedEpisodeCount()).isEqualTo(2);
        assertThat(result.series().totalKnownRuntime()).isEqualTo(90);
        assertThat(result.series().knownRuntimeEpisodeCount()).isEqualTo(2);
        assertThat(result.seasons()).extracting(SeriesProgressMetadataRefreshService.SeasonSnapshot::seasonNumber)
                .containsExactly(1, 2);
        verify(tmdbClient, never()).getTvFullDetails(anyString(), anyString());
        verify(tmdbClient, times(1)).getSeasonFullDetails(SERIES_ID, 1, "en-US");
        verify(tmdbClient, times(1)).getSeasonFullDetails(SERIES_ID, 2, "en-US");
        verify(seasonMetadataRepository, never()).findBySeriesTmdbIdAndSeasonNumber(anyString(), anyInt());
    }

    @Test
    @DisplayName("[refreshIfMissingOrExpired] Looks Up Tv Once And Each Positive Season Once")
    void shouldLookUpTvOnceAndEachPositiveSeasonOnceWhenSnapshotIsMissing() {
        when(tmdbClient.getTvFullDetails(SERIES_ID, "en-US"))
                .thenReturn(new TmdbLookupResult.Found<>(tv(summary(0), summary(2), summary(1), summary(2))));
        when(tmdbClient.getSeasonFullDetails(SERIES_ID, 1, "en-US"))
                .thenReturn(found(season(1, episode(1, "2026-09-01", 40))));
        when(tmdbClient.getSeasonFullDetails(SERIES_ID, 2, "en-US"))
                .thenReturn(found(season(2, episode(1, "2026-09-02", 50))));

        SeriesProgressMetadataRefreshService.Snapshot result =
                service.refreshIfMissingOrExpired(SERIES_ID, TODAY);

        assertThat(result.series().regularReleasedEpisodeCount()).isEqualTo(2);
        assertThat(result.series().totalKnownRuntime()).isEqualTo(90);
        verify(tmdbClient, times(1)).getTvFullDetails(SERIES_ID, "en-US");
        verify(tmdbClient, times(1)).getSeasonFullDetails(SERIES_ID, 1, "en-US");
        verify(tmdbClient, times(1)).getSeasonFullDetails(SERIES_ID, 2, "en-US");
    }

    @Test
    @DisplayName("[refresh] Fetches Regular Seasons Concurrently Through The Bounded Executor")
    void shouldFetchRegularSeasonsConcurrentlyThroughBoundedExecutor() throws Exception {
        CountDownLatch seasonsStarted = new CountDownLatch(2);
        CountDownLatch releaseSeasons = new CountDownLatch(1);
        when(tmdbClient.getSeasonFullDetails(anyString(), anyInt(), eq("en-US")))
                .thenAnswer(invocation -> {
                    int seasonNumber = invocation.getArgument(1);
                    seasonsStarted.countDown();
                    releaseSeasons.await(5, TimeUnit.SECONDS);
                    return found(season(seasonNumber, episode(1, "2026-09-01", 40)));
                });

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            var refresh = caller.submit(() -> service.refresh(SERIES_ID, tv(summary(1), summary(2)), TODAY));
            boolean bothSeasonsStarted = seasonsStarted.await(1, TimeUnit.SECONDS);
            releaseSeasons.countDown();

            assertThat(bothSeasonsStarted).as("all regular season lookups should be submitted before joining").isTrue();
            refresh.get(5, TimeUnit.SECONDS);
        } finally {
            releaseSeasons.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    @DisplayName("[refresh] Excludes Future Undated And Special Episodes")
    void shouldExcludeFutureUndatedAndSpecialEpisodes() {
        TmdbTvFullDetails details = tv(summary(1));
        when(tmdbClient.getSeasonFullDetails(SERIES_ID, 1, "en-US"))
                .thenReturn(found(season(1,
                        episode(0, "2026-09-01", 10),
                        episode(1, "2026-09-22", 40),
                        episode(2, "2026-09-24", 50),
                        episode(3, null, 60))));

        SeriesProgressMetadataRefreshService.Snapshot result = service.refresh(SERIES_ID, details, TODAY);

        assertThat(result.series().regularReleasedEpisodeCount()).isEqualTo(1);
        assertThat(result.series().totalKnownRuntime()).isEqualTo(40);
        assertThat(result.series().lastReleasedEpisodeDate()).isEqualTo(LocalDate.of(2026, 9, 22));
    }

    @Test
    @DisplayName("[refresh] Publishes Episode Metrics With Null Runtime When Runtime Is Incomplete")
    void shouldPublishEpisodeMetricsWithNullRuntimeWhenRuntimeIsIncomplete() {
        SeriesProgressMetadata existing = storedMetadata(LocalDateTime.of(2026, 9, 1, 10, 0));
        when(metadataRepository.findById(SERIES_ID)).thenReturn(Optional.of(existing));
        when(seasonMetadataRepository.findAllBySeriesTmdbIdIn(anyCollection())).thenReturn(List.of());
        when(tmdbClient.getSeasonFullDetails(SERIES_ID, 1, "en-US"))
                .thenReturn(found(season(1,
                        episode(1, "2026-09-01", null),
                        episode(2, "2026-09-02", 40))));

        SeriesProgressMetadataRefreshService.Snapshot result = service.refresh(SERIES_ID, tv(summary(1)), TODAY);

        assertThat(result.series().regularReleasedEpisodeCount()).isEqualTo(2);
        assertThat(result.series().knownRuntimeEpisodeCount()).isEqualTo(1);
        assertThat(result.series().totalKnownRuntime()).isNull();
        assertThat(result.series().lastReleasedEpisodeDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        verify(metadataRepository).save(argThat(metadata ->
                metadata.getTotalKnownRuntime() == null && metadata.getRuntimeVerifiedAt() == null));
        verify(seasonMetadataRepository).deleteAllBySeriesTmdbIdIn(List.of(SERIES_ID));
        verify(seasonMetadataRepository).saveAll(argThat(seasons -> {
            SeriesProgressSeasonMetadata season = (SeriesProgressSeasonMetadata) seasons.iterator().next();
            return season.getTotalKnownRuntime() == null;
        }));
    }

    @Test
    @DisplayName("[refreshIfMissingOrExpired] Uses A Fresh Snapshot Without TMDB")
    void shouldUseFreshSnapshotWithoutTmdb() {
        SeriesProgressMetadata fresh = storedMetadata(TODAY.atTime(8, 0));
        when(metadataRepository.findById(SERIES_ID)).thenReturn(Optional.of(fresh));
        when(seasonMetadataRepository.findAllBySeriesTmdbIdIn(List.of(SERIES_ID)))
                .thenReturn(List.of(seasonProjection(1, TODAY.atTime(8, 0))));

        SeriesProgressMetadataRefreshService.Snapshot result =
                service.refreshIfMissingOrExpired(SERIES_ID, TODAY);

        assertThat(result.series().seriesTmdbId()).isEqualTo(SERIES_ID);
        assertThat(result.seasons()).extracting(SeriesProgressMetadataRefreshService.SeasonSnapshot::seasonNumber)
                .containsExactly(1);
        verify(tmdbClient, never()).getTvFullDetails(anyString(), anyString());
        verifyNoSeasonFetches();
    }

    @Test
    @DisplayName("[refreshIfMissingOrExpired] Stale Calls Share One Refresh")
    void shouldShareOneRefreshForConcurrentStaleCalls() throws Exception {
        SeriesProgressMetadata stale = storedMetadata(TODAY.minusDays(2).atTime(8, 0));
        AtomicReference<SeriesProgressMetadata> stored = new AtomicReference<>(stale);
        CountDownLatch firstTvLookupStarted = new CountDownLatch(1);
        CountDownLatch allowFirstTvLookup = new CountDownLatch(1);
        when(metadataRepository.findById(SERIES_ID)).thenAnswer(invocation -> Optional.of(stored.get()));
        when(metadataRepository.save(any(SeriesProgressMetadata.class))).thenAnswer(invocation -> {
            SeriesProgressMetadata refreshed = invocation.getArgument(0);
            stored.set(refreshed);
            return refreshed;
        });
        when(seasonMetadataRepository.findAllBySeriesTmdbIdIn(anyCollection())).thenReturn(List.of());
        when(tmdbClient.getTvFullDetails(SERIES_ID, "en-US"))
                .thenAnswer(invocation -> {
                    firstTvLookupStarted.countDown();
                    allowFirstTvLookup.await(5, TimeUnit.SECONDS);
                    return new TmdbLookupResult.Found<>(tv(summary(1)));
                });
        when(tmdbClient.getSeasonFullDetails(SERIES_ID, 1, "en-US"))
                .thenReturn(found(season(1, episode(1, "2026-09-01", 40))));

        var callers = Executors.newFixedThreadPool(2);
        try {
            var first = callers.submit(() -> service.refreshIfMissingOrExpired(SERIES_ID, TODAY));
            assertThat(firstTvLookupStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var second = callers.submit(() -> service.refreshIfMissingOrExpired(SERIES_ID, TODAY));
            allowFirstTvLookup.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            allowFirstTvLookup.countDown();
            callers.shutdownNow();
        }

        verify(tmdbClient, times(1)).getTvFullDetails(SERIES_ID, "en-US");
        verify(tmdbClient, times(1)).getSeasonFullDetails(SERIES_ID, 1, "en-US");
    }

    @Test
    @DisplayName("[refreshIfMissingOrExpired] Shares A Failed Refresh And Preserves Stale Snapshot")
    void shouldShareFailedRefreshAndPreserveStaleSnapshotConcurrently() throws Exception {
        SeriesProgressMetadata stale = storedMetadata(TODAY.minusDays(2).atTime(8, 0));
        when(metadataRepository.findById(SERIES_ID)).thenReturn(Optional.of(stale));
        when(seasonMetadataRepository.findAllBySeriesTmdbIdIn(List.of(SERIES_ID)))
                .thenReturn(List.of(seasonProjection(1, stale.getRefreshedAt())));
        CountDownLatch tvLookupStarted = new CountDownLatch(1);
        CountDownLatch releaseTvLookup = new CountDownLatch(1);
        when(tmdbClient.getTvFullDetails(SERIES_ID, "en-US"))
                .thenAnswer(invocation -> {
                    tvLookupStarted.countDown();
                    releaseTvLookup.await(5, TimeUnit.SECONDS);
                    return new TmdbLookupResult.Found<>(tv(summary(1)));
                });
        when(tmdbClient.getSeasonFullDetails(SERIES_ID, 1, "en-US"))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        var callers = Executors.newFixedThreadPool(2);
        try {
            var first = callers.submit(() -> service.refreshIfMissingOrExpired(SERIES_ID, TODAY));
            assertThat(tvLookupStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var second = callers.submit(() -> service.refreshIfMissingOrExpired(SERIES_ID, TODAY));
            releaseTvLookup.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).series().totalKnownRuntime()).isEqualTo(100);
            assertThat(second.get(5, TimeUnit.SECONDS).series().totalKnownRuntime()).isEqualTo(100);
        } finally {
            releaseTvLookup.countDown();
            callers.shutdownNow();
        }

        verify(tmdbClient, times(1)).getTvFullDetails(SERIES_ID, "en-US");
        verify(tmdbClient, times(1)).getSeasonFullDetails(SERIES_ID, 1, "en-US");
        verify(metadataRepository, never()).save(any(SeriesProgressMetadata.class));
        verify(seasonMetadataRepository, never()).deleteAllBySeriesTmdbIdIn(anyCollection());
        verify(seasonMetadataRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("[refreshIfMissingOrExpired] Preserves Stale Snapshot When A Season Is Not Found")
    void shouldPreserveStaleSnapshotWhenSeasonIsNotFound() {
        SeriesProgressMetadata stale = storedMetadata(TODAY.minusDays(2).atTime(8, 0));
        when(metadataRepository.findById(SERIES_ID)).thenReturn(Optional.of(stale));
        when(seasonMetadataRepository.findAllBySeriesTmdbIdIn(List.of(SERIES_ID)))
                .thenReturn(List.of(seasonProjection(1, stale.getRefreshedAt())));
        when(tmdbClient.getTvFullDetails(SERIES_ID, "en-US"))
                .thenReturn(new TmdbLookupResult.Found<>(tv(summary(1))));
        when(tmdbClient.getSeasonFullDetails(SERIES_ID, 1, "en-US"))
                .thenReturn(new TmdbLookupResult.NotFound<>());

        SeriesProgressMetadataRefreshService.Snapshot result =
                service.refreshIfMissingOrExpired(SERIES_ID, TODAY);

        assertThat(result.series().totalKnownRuntime()).isEqualTo(100);
        verify(metadataRepository, never()).save(any(SeriesProgressMetadata.class));
        verify(seasonMetadataRepository, never()).deleteAllBySeriesTmdbIdIn(anyCollection());
        verify(seasonMetadataRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("[refresh] Rejects TV Details For A Different Series")
    void shouldRejectTvDetailsForDifferentSeries() {
        assertThatThrownBy(() -> service.refresh(SERIES_ID, tv("different", summary(1)), TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    @DisplayName("[refreshIfMissingOrExpired] Fails When Cold TV Lookup Is Unavailable")
    void shouldFailWhenColdTvLookupIsUnavailable() {
        when(tmdbClient.getTvFullDetails(SERIES_ID, "en-US"))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.refreshIfMissingOrExpired(SERIES_ID, TODAY))
                .isInstanceOf(TmdbUnavailableException.class);
        verify(metadataRepository, never()).save(any(SeriesProgressMetadata.class));
    }

    private void verifyNoSeasonFetches() {
        verify(tmdbClient, never()).getSeasonFullDetails(anyString(), anyInt(), anyString());
    }

    private static SeriesProgressMetadata storedMetadata(LocalDateTime refreshedAt) {
        return SeriesProgressMetadata.builder()
                .seriesTmdbId(SERIES_ID)
                .regularReleasedEpisodeCount(2)
                .totalKnownRuntime(100)
                .knownRuntimeEpisodeCount(2)
                .lastReleasedEpisodeDate(LocalDate.of(2026, 9, 2))
                .refreshedAt(refreshedAt)
                .runtimeVerifiedAt(refreshedAt)
                .build();
    }

    private static SeriesProgressSeasonMetadataRepository.SeriesProgressSeasonMetadataProjection seasonProjection(
            int seasonNumber, LocalDateTime refreshedAt) {
        return new SeriesProgressSeasonMetadataRepository.SeriesProgressSeasonMetadataProjection() {
            public String getSeriesTmdbId() { return SERIES_ID; }
            public Integer getSeasonNumber() { return seasonNumber; }
            public Integer getRegularReleasedEpisodeCount() { return 1; }
            public Integer getTotalKnownRuntime() { return 40; }
            public Integer getKnownRuntimeEpisodeCount() { return 1; }
            public LocalDate getLastReleasedEpisodeDate() { return LocalDate.of(2026, 9, 1); }
            public LocalDateTime getRefreshedAt() { return refreshedAt; }
        };
    }

    private static TmdbLookupResult<TmdbSeasonFullDetails> found(TmdbSeasonFullDetails season) {
        return new TmdbLookupResult.Found<>(season);
    }

    private static TmdbTvFullDetails tv(TmdbSeasonSummary... summaries) {
        return tv(SERIES_ID, summaries);
    }

    private static TmdbTvFullDetails tv(String seriesId, TmdbSeasonSummary... summaries) {
        return new TmdbTvFullDetails(
                seriesId, null, null, null, null, null, null, null, null, null, null,
                List.of(summaries), null, null, null, null, null, null, null, null, null, null);
    }

    private static TmdbSeasonSummary summary(int seasonNumber) {
        return new TmdbSeasonSummary(seasonNumber, null, null, null, null, null);
    }

    private static TmdbSeasonFullDetails season(int seasonNumber, TmdbEpisodeSummary... episodes) {
        return new TmdbSeasonFullDetails(
                null, null, null, null, null, seasonNumber, Arrays.asList(episodes), null, null);
    }

    private static TmdbEpisodeSummary episode(int episodeNumber, String airDate, Integer runtime) {
        return new TmdbEpisodeSummary(episodeNumber, null, null, airDate, runtime, null, null);
    }
}
