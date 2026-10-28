package com.watchwise.watchwise_api.contentreleasedatesnapshot.service.impl;

import com.watchwise.watchwise_api.calendar.service.CalendarMovieReleaseDateSelector;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.common.transaction.RefreshGenerationSequence;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeCalculator;
import com.watchwise.watchwise_api.content.service.impl.SeriesRuntimeAggregateServiceImpl;
import com.watchwise.watchwise_api.contentreleasedatesnapshot.entity.ContentReleaseDateSnapshot;
import com.watchwise.watchwise_api.contentreleasedatesnapshot.repository.ContentReleaseDateSnapshotRepository;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import com.watchwise.watchwise_api.seriesprogress.entity.SeriesProgressMetadata;
import com.watchwise.watchwise_api.seriesprogress.entity.SeriesProgressSeasonMetadata;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressMetadataRepository;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressSeasonMetadataRepository;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataCalculator;
import com.watchwise.watchwise_api.seriesprogress.service.impl.SeriesProgressMetadataRefreshServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        com.watchwise.watchwise_api.common.transaction.PostgresAdvisoryLock.class,
        RefreshGenerationSequence.class
})
class RefreshFencingPostgresIntegrationTest {

    private static final String SERIES_ID = "1399";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private ContentReleaseDateSnapshotRepository releaseDateRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private SeriesProgressMetadataRepository seriesProgressRepository;
    @Autowired private SeriesProgressSeasonMetadataRepository seasonProgressRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AdvisoryLock advisoryLock;
    @Autowired private RefreshGenerationSequence refreshGenerationSequence;

    private final List<ExecutorService> executors = new ArrayList<>();

    @BeforeEach
    void clearFixtures() {
        seasonProgressRepository.deleteAll();
        seriesProgressRepository.deleteAll();
        releaseDateRepository.deleteAll();
        contentRepository.deleteAll();
    }

    @AfterEach
    void stopExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void expiredReleaseDateLeaseHolderCannotOverwriteNewClaimantsSnapshot() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        ContentReleaseDateSnapshot previous = releaseDateRepository.saveAndFlush(ContentReleaseDateSnapshot.builder()
                .tmdbId("550")
                .type(ContentType.MOVIE)
                .region("BR")
                .releaseDate(LocalDate.of(2027, 1, 1))
                .status(ContentReleaseDateSnapshot.Status.FOUND)
                .lastCheckedAt(now.minusDays(2))
                .nextCheckAt(now.minusMinutes(1))
                .build());
        TmdbClient olderTmdb = mock(TmdbClient.class);
        TmdbClient newerTmdb = mock(TmdbClient.class);
        CountDownLatch olderFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseOlderFetch = new CountDownLatch(1);
        when(olderTmdb.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenAnswer(invocation -> {
                    olderFetchStarted.countDown();
                    assertThat(releaseOlderFetch.await(5, TimeUnit.SECONDS)).isTrue();
                    return new TmdbLookupResult.NotFound<>();
                });
        when(newerTmdb.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(movieDetails("2027-02-03")));

        ContentReleaseDateSnapshotServiceImpl olderService = releaseDateService(olderTmdb);
        ContentReleaseDateSnapshotServiceImpl newerService = releaseDateService(newerTmdb);
        ExecutorService olderWorker = namedExecutor("old-release-date-worker");
        try {
            Future<?> older = olderWorker.submit(olderService::refreshDue);
            assertThat(olderFetchStarted.await(5, TimeUnit.SECONDS)).isTrue();

            jdbcTemplate.update("UPDATE content_release_date_snapshots SET next_check_at = ? WHERE id = ?",
                    LocalDateTime.now().minusMinutes(1), previous.getId());
            newerService.refreshDue();
            assertThat(releaseDateRepository.findById(previous.getId()).orElseThrow().getReleaseDate())
                    .isEqualTo(LocalDate.of(2027, 2, 3));

            releaseOlderFetch.countDown();
            older.get(5, TimeUnit.SECONDS);
        } finally {
            releaseOlderFetch.countDown();
            olderWorker.shutdownNow();
            assertThat(olderWorker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(releaseDateRepository.findById(previous.getId()).orElseThrow().getReleaseDate())
                .isEqualTo(LocalDate.of(2027, 2, 3));
    }

    @Test
    void olderSeriesProgressSnapshotCannotReplaceNewerMetadataAndSeasonSet() throws Exception {
        TmdbClient tmdbClient = mock(TmdbClient.class);
        CountDownLatch oldSeasonStarted = new CountDownLatch(1);
        CountDownLatch releaseOldSeason = new CountDownLatch(1);
        when(tmdbClient.getSeasonFullDetails(SERIES_ID, 1, "en-US")).thenAnswer(invocation -> {
            oldSeasonStarted.countDown();
            assertThat(releaseOldSeason.await(5, TimeUnit.SECONDS)).isTrue();
            return found(season(1, 35));
        });
        when(tmdbClient.getSeasonFullDetails(SERIES_ID, 2, "en-US"))
                .thenReturn(found(season(2, 70)));

        SeriesProgressMetadataRefreshServiceImpl olderService = seriesProgressService(
                tmdbClient, namedExecutor("old-progress-season"));
        SeriesProgressMetadataRefreshServiceImpl newerService = seriesProgressService(
                tmdbClient, namedExecutor("new-progress-season"));
        ExecutorService olderWorker = namedExecutor("old-progress-call");
        try {
            Future<?> older = olderWorker.submit(() -> olderService.refresh(SERIES_ID, tv(1), TODAY));
            assertThat(oldSeasonStarted.await(5, TimeUnit.SECONDS)).isTrue();

            newerService.refresh(SERIES_ID, tv(2), TODAY);
            assertThat(seriesProgressRepository.findById(SERIES_ID).orElseThrow().getTotalKnownRuntime())
                    .isEqualTo(70);
            assertThat(seasonProgressRepository.findAllBySeriesTmdbIdIn(List.of(SERIES_ID)))
                    .extracting(SeriesProgressSeasonMetadataRepository.SeriesProgressSeasonMetadataProjection::getSeasonNumber)
                    .containsExactly(2);

            releaseOldSeason.countDown();
            older.get(5, TimeUnit.SECONDS);
        } finally {
            releaseOldSeason.countDown();
            olderWorker.shutdownNow();
            assertThat(olderWorker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(seriesProgressRepository.findById(SERIES_ID).orElseThrow().getTotalKnownRuntime())
                .isEqualTo(70);
        assertThat(seasonProgressRepository.findAllBySeriesTmdbIdIn(List.of(SERIES_ID)))
                .extracting(SeriesProgressSeasonMetadataRepository.SeriesProgressSeasonMetadataProjection::getSeasonNumber)
                .containsExactly(2);
    }

    @Test
    void olderRuntimeObservationWithSameEpisodeCountCannotOverwriteNewerAggregate() throws Exception {
        Content content = contentRepository.saveAndFlush(Content.builder()
                .tmdbId(SERIES_ID)
                .type(ContentType.SERIES)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        TmdbClient tmdbClient = mock(TmdbClient.class);
        CountDownLatch oldSeasonStarted = new CountDownLatch(1);
        CountDownLatch releaseOldSeasons = new CountDownLatch(1);
        when(tmdbClient.getSeasonFullDetails(eq(SERIES_ID), anyInt(), anyString())).thenAnswer(invocation -> {
            int seasonNumber = invocation.getArgument(1);
            boolean older = Thread.currentThread().getName().startsWith("old-runtime-season");
            if (older) {
                oldSeasonStarted.countDown();
                assertThat(releaseOldSeasons.await(5, TimeUnit.SECONDS)).isTrue();
            }
            int runtime = older ? (seasonNumber == 1 ? 40 : 50) : (seasonNumber == 1 ? 60 : 70);
            return found(season(seasonNumber, runtime));
        });

        SeriesRuntimeAggregateServiceImpl olderService = runtimeService(
                tmdbClient, namedExecutor("old-runtime-season"));
        SeriesRuntimeAggregateServiceImpl newerService = runtimeService(
                tmdbClient, namedExecutor("new-runtime-season"));
        TmdbTvDetails details = new TmdbTvDetails(SERIES_ID, "Returning Series", null,
                List.of(summary(1), summary(2)));
        ExecutorService olderWorker = namedExecutor("old-runtime-call");
        try {
            Future<?> older = olderWorker.submit(() -> olderService.reconcileBeforeFreezing(content, details));
            assertThat(oldSeasonStarted.await(5, TimeUnit.SECONDS)).isTrue();

            newerService.reconcileBeforeFreezing(content, details);
            assertThat(contentRepository.findById(content.getId()).orElseThrow().getTotalRuntimeMinutes())
                    .isEqualTo(130);

            releaseOldSeasons.countDown();
            older.get(5, TimeUnit.SECONDS);
        } finally {
            releaseOldSeasons.countDown();
            olderWorker.shutdownNow();
            assertThat(olderWorker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        Content persisted = contentRepository.findById(content.getId()).orElseThrow();
        assertThat(persisted.getRuntimeReportedEpisodeCount()).isEqualTo(2);
        assertThat(persisted.getTotalRuntimeMinutes()).isEqualTo(130);
    }

    private ContentReleaseDateSnapshotServiceImpl releaseDateService(TmdbClient tmdbClient) {
        return new ContentReleaseDateSnapshotServiceImpl(
                releaseDateRepository,
                tmdbClient,
                transactionExecutor(),
                mock(ExecutorService.class),
                mock(ExecutorService.class),
                refreshGenerationSequence);
    }

    private SeriesProgressMetadataRefreshServiceImpl seriesProgressService(
            TmdbClient tmdbClient, ExecutorService seasonExecutor) {
        return new SeriesProgressMetadataRefreshServiceImpl(
                tmdbClient,
                seriesProgressRepository,
                seasonProgressRepository,
                new SeriesProgressMetadataCalculator(new SeriesRuntimeCalculator()),
                seasonExecutor,
                mock(ExecutorService.class),
                transactionExecutor(),
                advisoryLock,
                refreshGenerationSequence);
    }

    private SeriesRuntimeAggregateServiceImpl runtimeService(TmdbClient tmdbClient, ExecutorService seasonExecutor) {
        return new SeriesRuntimeAggregateServiceImpl(
                tmdbClient,
                contentRepository,
                new SeriesRuntimeCalculator(),
                seasonExecutor,
                transactionExecutor(),
                refreshGenerationSequence);
    }

    private NewTransactionExecutor transactionExecutor() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return new NewTransactionExecutor() {
            @Override
            public <T> T runInNewTransaction(Supplier<T> action) {
                return template.execute(status -> action.get());
            }
        };
    }

    private ExecutorService namedExecutor(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2,
                task -> new Thread(task, prefix + "-" + sequence.incrementAndGet()));
        executors.add(executor);
        return executor;
    }

    private static TmdbMovieFullDetails movieDetails(String date) {
        return new TmdbMovieFullDetails("550", "Movie", new TmdbMovieReleaseDates("550", List.of(
                new TmdbRegionReleaseDates("BR", List.of(
                        new TmdbMovieReleaseDate(null, null, date, null, 3))))));
    }

    private static TmdbTvFullDetails tv(int seasonNumber) {
        return new TmdbTvFullDetails(
                SERIES_ID, null, null, null, null, null, null, null, null, null, null,
                List.of(summary(seasonNumber)), null, null, null, null, null, null, null, null, null, null);
    }

    private static TmdbSeasonSummary summary(int seasonNumber) {
        return new TmdbSeasonSummary(seasonNumber, null, null, null, 1, null);
    }

    private static TmdbSeasonFullDetails season(int seasonNumber, int runtime) {
        return new TmdbSeasonFullDetails(null, null, null, null, null, seasonNumber,
                List.of(new TmdbEpisodeSummary(1, null, null, "2026-09-10", runtime, null, null)), null, null);
    }

    private static TmdbLookupResult<TmdbSeasonFullDetails> found(TmdbSeasonFullDetails season) {
        return new TmdbLookupResult.Found<>(season);
    }
}
