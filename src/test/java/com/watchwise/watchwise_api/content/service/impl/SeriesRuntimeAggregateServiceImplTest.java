package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    private static TmdbSeasonSummary summary(int number, int count) {
        return new TmdbSeasonSummary(number, null, null, null, count, null);
    }

    private static TmdbSeasonFullDetails season(int number, Integer... runtimes) {
        return new TmdbSeasonFullDetails(null, null, null, null, null, number,
                java.util.Arrays.stream(runtimes).map(runtime -> new TmdbEpisodeSummary(null, null, null, null, runtime, null, null)).toList(), null, null);
    }
}
