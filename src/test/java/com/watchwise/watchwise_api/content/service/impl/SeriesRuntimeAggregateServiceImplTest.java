package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeAggregate;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeCalculator;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeResolution;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class SeriesRuntimeAggregateServiceImplTest {

    @Mock private TmdbClient tmdbClient;
    @Mock private ContentRepository contentRepository;
    @Mock private NewTransactionExecutor newTransactionExecutor;

    private ExecutorService seasonExecutor;
    private SeriesRuntimeAggregateServiceImpl service;

    @BeforeEach
    void setUp() {
        seasonExecutor = Executors.newFixedThreadPool(2);
        org.mockito.Mockito.lenient().when(newTransactionExecutor.runInNewTransaction(any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        service = new SeriesRuntimeAggregateServiceImpl(
                tmdbClient, contentRepository, new SeriesRuntimeCalculator(), seasonExecutor, newTransactionExecutor);
    }

    @AfterEach
    void tearDown() {
        seasonExecutor.shutdownNow();
    }

    @Test
    @DisplayName("[resolve] Reuses A Recent Complete Baseline When Reported Episode Count Is Unchanged")
    void shouldReuseRecentCompleteBaselineWhenReportedEpisodeCountIsUnchanged() {
        Content content = baselineContent(LocalDateTime.now().minusHours(1));

        SeriesRuntimeResolution result = service.resolve(content, details(summary(1, 50), summary(2, 50)), "pt-BR");

        assertThat(result.aggregate()).isEqualTo(new SeriesRuntimeAggregate(4_800, 48, 100, 100));
        assertThat(result.seasonsFetchedForAggregate()).isEmpty();
        assertThat(result.seasonFetchAttempted()).isFalse();
        verify(tmdbClient, never()).getSeasonFullDetails(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("[resolve] Refreshes An Expired Baseline From Every Regular Season")
    void shouldRefreshExpiredBaselineFromEveryRegularSeason() {
        Content content = baselineContent(LocalDateTime.now().minusHours(169));
        when(tmdbClient.getSeasonFullDetails("1399", 1, "pt-BR"))
                .thenReturn(found(season(1, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48)));
        when(tmdbClient.getSeasonFullDetails("1399", 2, "pt-BR"))
                .thenReturn(found(season(2, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48)));

        SeriesRuntimeResolution result = service.resolve(content, details(summary(1, 50), summary(2, 50)), "pt-BR");

        assertThat(result.aggregate()).isEqualTo(new SeriesRuntimeAggregate(4_800, 48, 100, 100));
        verify(tmdbClient, times(2)).getSeasonFullDetails(eq("1399"), anyInt(), eq("pt-BR"));
        verify(contentRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getTotalRuntimeMinutes().equals(4_800)
                        && saved.getRuntimeMinutes().equals(48)
                        && saved.getRuntimeMinutesEpisodeCount().equals(100)));
    }

    @Test
    @DisplayName("[resolve] Preserves The Existing Baseline When A Season Cannot Be Loaded")
    void shouldPreserveExistingBaselineWhenASeasonCannotBeLoaded() {
        Content content = baselineContent(LocalDateTime.now().minusHours(169));
        when(tmdbClient.getSeasonFullDetails("1399", 1, "pt-BR")).thenReturn(found(season(1, 48)));
        when(tmdbClient.getSeasonFullDetails("1399", 2, "pt-BR")).thenReturn(new TmdbLookupResult.Unavailable<>());

        SeriesRuntimeResolution result = service.resolve(content, details(summary(1, 1), summary(2, 1)), "pt-BR");

        assertThat(result.aggregate()).isEqualTo(new SeriesRuntimeAggregate(4_800, 48, 100, 100));
        assertThat(result.seasonFetchAttempted()).isTrue();
        verify(contentRepository, never()).save(content);
    }

    @Test
    @DisplayName("[resolve] Refreshes When The Reported Episode Count Changes")
    void shouldRefreshWhenReportedEpisodeCountChanges() {
        Content content = baselineContent(LocalDateTime.now().minusHours(1));
        when(tmdbClient.getSeasonFullDetails("1399", 1, "pt-BR")).thenReturn(found(season(1, 48)));
        when(tmdbClient.getSeasonFullDetails("1399", 2, "pt-BR")).thenReturn(found(season(2, 48)));

        service.resolve(content, details(summary(1, 1), summary(2, 1)), "pt-BR");

        verify(tmdbClient, times(2)).getSeasonFullDetails(eq("1399"), anyInt(), eq("pt-BR"));
    }

    @Test
    @DisplayName("[incrementForNewEpisode] Updates The Locked Baseline Without Marking It Fully Verified")
    void shouldUpdateLockedBaselineWithoutMarkingItFullyVerified() {
        Content content = baselineContent(LocalDateTime.now().minusHours(1));
        LocalDateTime verifiedAt = content.getRuntimeAggregateVerifiedAt();
        when(tmdbClient.getEpisodeFullDetails("1399", 3, 1, "en-US"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbEpisodeFullDetails(null, null, null, null, 1, 3, 50, null, null)));
        when(contentRepository.findByIdForUpdate(content.getId())).thenReturn(java.util.Optional.of(content));

        service.incrementForNewEpisode(content, 3, 1, 101);

        assertThat(content.getTotalRuntimeMinutes()).isEqualTo(4_850);
        assertThat(content.getRuntimeMinutesEpisodeCount()).isEqualTo(101);
        assertThat(content.getRuntimeMinutes()).isEqualTo(48);
        assertThat(content.getRuntimeReportedEpisodeCount()).isEqualTo(101);
        assertThat(content.getRuntimeAggregateVerifiedAt()).isEqualTo(verifiedAt);
        verify(contentRepository).save(content);
    }

    @Test
    @DisplayName("[incrementForNewEpisode] Ignores A Duplicate Or Stale Reported Episode Counter")
    void shouldIgnoreDuplicateOrStaleReportedEpisodeCounter() {
        Content content = baselineContent(LocalDateTime.now().minusHours(1));
        when(tmdbClient.getEpisodeFullDetails("1399", 3, 1, "en-US"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbEpisodeFullDetails(null, null, null, null, 1, 3, 50, null, null)));
        when(contentRepository.findByIdForUpdate(content.getId())).thenReturn(java.util.Optional.of(content));

        service.incrementForNewEpisode(content, 3, 1, 100);
        service.incrementForNewEpisode(content, 3, 1, 99);

        assertThat(content.getTotalRuntimeMinutes()).isEqualTo(4_800);
        assertThat(content.getRuntimeMinutesEpisodeCount()).isEqualTo(100);
        verify(contentRepository, never()).save(content);
    }

    @Test
    @DisplayName("[incrementForNewEpisode] Uses The Locked Database Baseline Instead Of The Caller Snapshot")
    void shouldUseLockedDatabaseBaselineInsteadOfCallerSnapshot() {
        Content callerSnapshot = baselineContent(LocalDateTime.now().minusHours(1));
        Content locked = baselineContent(LocalDateTime.now().minusHours(1));
        locked.setId(callerSnapshot.getId());
        locked.setTotalRuntimeMinutes(4_850);
        locked.setRuntimeMinutesEpisodeCount(101);
        locked.setRuntimeReportedEpisodeCount(101);
        when(tmdbClient.getEpisodeFullDetails("1399", 3, 2, "en-US"))
                .thenReturn(foundEpisode(50));
        when(contentRepository.findByIdForUpdate(callerSnapshot.getId())).thenReturn(Optional.of(locked));

        service.incrementForNewEpisode(callerSnapshot, 3, 2, 102);

        assertThat(locked.getTotalRuntimeMinutes()).isEqualTo(4_900);
        assertThat(locked.getRuntimeMinutesEpisodeCount()).isEqualTo(102);
        assertThat(locked.getRuntimeReportedEpisodeCount()).isEqualTo(102);
        assertThat(callerSnapshot.getTotalRuntimeMinutes()).isEqualTo(4_800);
        verify(contentRepository).save(locked);
    }

    @ParameterizedTest(name = "[incrementForNewEpisode] Gap Reconciles Without Episode Runtime ({index})")
    @MethodSource("episodeLookupsWithoutRuntime")
    void shouldReconcileReportedCountGapOutsideTransactionWithoutEpisodeRuntime(
            TmdbLookupResult<TmdbEpisodeFullDetails> episodeLookup) {
        Content callerSnapshot = baselineContent(LocalDateTime.now().minusHours(1));
        Content locked = baselineContent(LocalDateTime.now().minusHours(1));
        locked.setId(callerSnapshot.getId());
        AtomicBoolean transactionActive = new AtomicBoolean();
        doAnswer(invocation -> {
            assertThat(transactionActive.compareAndSet(false, true)).isTrue();
            try {
                return ((java.util.function.Supplier<?>) invocation.getArgument(0)).get();
            } finally {
                transactionActive.set(false);
            }
        }).when(newTransactionExecutor).runInNewTransaction(any());
        when(contentRepository.findByIdForUpdate(callerSnapshot.getId())).thenReturn(Optional.of(locked));
        when(tmdbClient.getEpisodeFullDetails("1399", 3, 2, "en-US")).thenReturn(episodeLookup);
        when(tmdbClient.getTvDetails("1399")).thenAnswer(invocation -> {
            assertThat(transactionActive).isFalse();
            return Optional.of(new TmdbTvDetails("1399", "Returning Series", null, List.of(summary(1, 102))));
        });
        when(tmdbClient.getSeasonFullDetails("1399", 1, "en-US"))
                .thenReturn(found(season(1, 40, 60)));

        service.incrementForNewEpisode(callerSnapshot, 3, 2, 102);

        assertThat(locked.getTotalRuntimeMinutes()).isEqualTo(100);
        assertThat(locked.getRuntimeMinutes()).isEqualTo(50);
        assertThat(locked.getRuntimeMinutesEpisodeCount()).isEqualTo(2);
        assertThat(locked.getRuntimeReportedEpisodeCount()).isEqualTo(102);
        verify(tmdbClient).getTvDetails("1399");
        verify(contentRepository).save(locked);
    }

    @Test
    @DisplayName("[incrementForNewEpisode] Preserves Locked Baseline When Gap Summary Is Unavailable")
    void shouldPreserveLockedBaselineWhenGapSummaryIsUnavailable() {
        Content callerSnapshot = baselineContent(LocalDateTime.now().minusHours(1));
        Content locked = baselineContent(LocalDateTime.now().minusHours(1));
        locked.setId(callerSnapshot.getId());
        when(contentRepository.findByIdForUpdate(callerSnapshot.getId())).thenReturn(Optional.of(locked));
        when(tmdbClient.getEpisodeFullDetails("1399", 3, 2, "en-US"))
                .thenReturn(new TmdbLookupResult.Unavailable<>());
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.empty());

        service.incrementForNewEpisode(callerSnapshot, 3, 2, 102);

        assertThat(locked.getTotalRuntimeMinutes()).isEqualTo(4_800);
        assertThat(locked.getRuntimeMinutes()).isEqualTo(48);
        assertThat(locked.getRuntimeMinutesEpisodeCount()).isEqualTo(100);
        assertThat(locked.getRuntimeReportedEpisodeCount()).isEqualTo(100);
        verify(tmdbClient).getTvDetails("1399");
        verify(contentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[resolve] Concurrent Callers Share One Reconciliation And Release The Lock Entry")
    void shouldShareOneReconciliationAndReleaseLockEntryForConcurrentCallers() throws Exception {
        Content content = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        TmdbTvFullDetails freshDetails = details(summary(1, 1));
        CountDownLatch seasonFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseSeasonFetch = new CountDownLatch(1);
        when(contentRepository.findById(content.getId())).thenReturn(Optional.of(content));
        when(tmdbClient.getSeasonFullDetails("1399", 1, "pt-BR")).thenAnswer(invocation -> {
            seasonFetchStarted.countDown();
            assertThat(releaseSeasonFetch.await(5, TimeUnit.SECONDS)).isTrue();
            return found(season(1, 48));
        });

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<SeriesRuntimeResolution> first = callers.submit(() -> service.resolve(content, freshDetails, "pt-BR"));
            assertThat(seasonFetchStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<SeriesRuntimeResolution> second = callers.submit(() -> service.resolve(content, freshDetails, "pt-BR"));
            awaitLockParticipants(content.getId(), 2);

            releaseSeasonFetch.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).aggregate())
                    .isEqualTo(new SeriesRuntimeAggregate(48, 48, 1, 1));
            assertThat(second.get(5, TimeUnit.SECONDS).aggregate())
                    .isEqualTo(new SeriesRuntimeAggregate(48, 48, 1, 1));
            verify(tmdbClient).getSeasonFullDetails("1399", 1, "pt-BR");
            assertThat(resolutionLocks()).isEmpty();
        } finally {
            releaseSeasonFetch.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    @DisplayName("[resolve] Reuses A Legacy Baseline And Derives Its Missing Average")
    void shouldReuseLegacyBaselineAndDeriveMissingAverage() {
        Content content = baselineContent(LocalDateTime.now().minusHours(1));
        content.setRuntimeMinutes(null);

        SeriesRuntimeResolution result = service.resolve(content, details(summary(1, 50), summary(2, 50)), "pt-BR");

        assertThat(result.aggregate().averageRuntimeMinutes()).isEqualTo(48);
        verifyNoInteractions(tmdbClient);
    }

    private static Content baselineContent(LocalDateTime verifiedAt) {
        return Content.builder().id(java.util.UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .totalRuntimeMinutes(4_800).runtimeMinutes(48).runtimeMinutesEpisodeCount(100)
                .runtimeReportedEpisodeCount(100).runtimeAggregateVerifiedAt(verifiedAt)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private static TmdbTvFullDetails details(TmdbSeasonSummary... summaries) {
        return new TmdbTvFullDetails("1399", null, null, null, null, null, null, null, null, null,
                null, List.of(summaries), null, null, null, null, null, null, null, null, null);
    }

    private static TmdbLookupResult<TmdbSeasonFullDetails> found(TmdbSeasonFullDetails season) {
        return new TmdbLookupResult.Found<>(season);
    }

    private static TmdbLookupResult<TmdbEpisodeFullDetails> foundEpisode(Integer runtime) {
        return new TmdbLookupResult.Found<>(
                new TmdbEpisodeFullDetails(null, null, null, null, 2, 3, runtime, null, null));
    }

    private static Stream<TmdbLookupResult<TmdbEpisodeFullDetails>> episodeLookupsWithoutRuntime() {
        return Stream.of(
                new TmdbLookupResult.Unavailable<>(),
                new TmdbLookupResult.NotFound<>(),
                foundEpisode(null));
    }

    private void awaitLockParticipants(UUID contentId, int expectedParticipants) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Object lockEntry = resolutionLocks().get(contentId);
            Integer participants = lockEntry == null ? null
                    : (Integer) ReflectionTestUtils.getField(lockEntry, "participants");
            if (participants != null && participants == expectedParticipants) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError("Timed out waiting for " + expectedParticipants + " lock participants");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<UUID, Object> resolutionLocks() {
        return (ConcurrentHashMap<UUID, Object>) ReflectionTestUtils.getField(service, "resolutionLocks");
    }

    private static TmdbSeasonSummary summary(int number, int count) {
        return new TmdbSeasonSummary(number, null, null, null, count, null);
    }

    private static TmdbSeasonFullDetails season(int number, Integer... runtimes) {
        return new TmdbSeasonFullDetails(null, null, null, null, null, number,
                java.util.Arrays.stream(runtimes).map(runtime -> new TmdbEpisodeSummary(null, null, null, null, runtime, null, null)).toList(), null, null);
    }
}
