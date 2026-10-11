package com.watchwise.watchwise_api.common.tmdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbCacheConfigTest {

    @Test
    @DisplayName("[contentCardMetadataResolutionExecutor] Should Use Eight Workers And A Bounded Queue - When Created")
    void shouldUseEightWorkersAndABoundedQueueWhenCreated() {
        ExecutorService executor = new TmdbCacheConfig().contentCardMetadataResolutionExecutor();
        try {
            ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
            assertThat(pool.getCorePoolSize()).isEqualTo(8);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(8);
            assertThat(pool.getQueue().remainingCapacity()).isEqualTo(50);
        } finally {
            executor.shutdownNow();
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
