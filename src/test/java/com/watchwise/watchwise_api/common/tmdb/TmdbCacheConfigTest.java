package com.watchwise.watchwise_api.common.tmdb;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbCacheConfigTest {

    @Test
    void appliesConfiguredMaximumSizeToDetailsCache() {
        var cache = new TmdbCacheConfig().tmdbMovieFullDetailsCache(24, 2);
        var notFound = new TmdbLookupResult.NotFound<TmdbMovieFullDetails>();

        cache.put("movie-1", notFound);
        cache.put("movie-2", notFound);
        cache.put("movie-3", notFound);
        cache.cleanUp();

        assertThat(cache.estimatedSize()).isLessThanOrEqualTo(2);
    }

    @Test
    void limitsEpisodeRatingsMetadataWorkersAndQueue() throws Exception {
        ExecutorService executor = new TmdbCacheConfig().episodeRatingsMetadataExecutor();
        try {
            assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
            ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
            assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
            assertThat(pool.getQueue().remainingCapacity()).isEqualTo(100);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void blocksEpisodeRatingsMetadataSubmissionWhenWorkersAndQueueAreFull() throws Exception {
        ExecutorService executor = new TmdbCacheConfig().episodeRatingsMetadataExecutor();
        ExecutorService submitter = Executors.newSingleThreadExecutor();
        CountDownLatch workersRunning = new CountDownLatch(4);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch overflowStarted = new CountDownLatch(1);
        try {
            for (int index = 0; index < 4; index++) {
                executor.execute(() -> {
                    workersRunning.countDown();
                    await(releaseWorkers);
                });
            }
            assertThat(workersRunning.await(1, TimeUnit.SECONDS)).isTrue();
            for (int index = 0; index < 100; index++) {
                executor.execute(() -> {});
            }

            Future<?> overflowSubmission = submitter.submit(
                    () -> executor.execute(overflowStarted::countDown));
            assertThat(overflowStarted.await(150, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(overflowSubmission.isDone()).isFalse();

            releaseWorkers.countDown();
            overflowSubmission.get(2, TimeUnit.SECONDS);
            assertThat(overflowStarted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseWorkers.countDown();
            submitter.shutdownNow();
            executor.shutdownNow();
            assertThat(submitter.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void keepsSeasonFetchesRunnableWhenTheBoundedRefreshExecutorIsSaturated() throws Exception {
        TmdbCacheConfig config = new TmdbCacheConfig();
        ExecutorService refreshExecutor = config.calendarScheduleRefreshExecutor();
        ExecutorService seasonExecutor = config.tmdbSeasonFetchExecutor();
        CountDownLatch refreshesRunning = new CountDownLatch(4);
        CountDownLatch releaseRefreshes = new CountDownLatch(1);
        CountDownLatch queuedRefreshStarted = new CountDownLatch(1);
        AtomicBoolean seasonFetchCompleted = new AtomicBoolean();
        try {
            for (int index = 0; index < 4; index++) {
                refreshExecutor.submit(() -> {
                    refreshesRunning.countDown();
                    await(releaseRefreshes);
                });
            }
            assertThat(refreshesRunning.await(1, TimeUnit.SECONDS)).isTrue();
            refreshExecutor.submit(queuedRefreshStarted::countDown);

            seasonExecutor.submit(() -> seasonFetchCompleted.set(true)).get(1, TimeUnit.SECONDS);

            assertThat(seasonFetchCompleted).isTrue();
            assertThat(queuedRefreshStarted.await(100, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            releaseRefreshes.countDown();
            refreshExecutor.shutdownNow();
            seasonExecutor.shutdownNow();
            assertThat(refreshExecutor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            assertThat(seasonExecutor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
