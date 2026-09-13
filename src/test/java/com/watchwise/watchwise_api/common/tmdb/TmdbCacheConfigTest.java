package com.watchwise.watchwise_api.common.tmdb;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbCacheConfigTest {

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
