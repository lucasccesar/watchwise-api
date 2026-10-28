package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieDetails;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.common.transaction.RefreshGenerationSequence;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeAggregateService;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.repository.TrackedContentStateRepository;
import com.watchwise.watchwise_api.notification.tracking.ContentChangeDetector;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        com.watchwise.watchwise_api.common.transaction.PostgresAdvisoryLock.class,
        RefreshGenerationSequence.class
})
class ContentTrackingServicePostgresIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private ContentRepository contentRepository;
    @Autowired private TrackedContentStateRepository trackedContentStateRepository;
    @Autowired private AdvisoryLock advisoryLock;
    @Autowired private RefreshGenerationSequence refreshGenerationSequence;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private Content movie;
    private final WatchlistEntryRepository watchlistEntryRepository = mock(WatchlistEntryRepository.class);
    private final DiaryEntryRepository diaryEntryRepository = mock(DiaryEntryRepository.class);
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ContentService contentService = mock(ContentService.class);
    private final SeriesRuntimeAggregateService runtimeAggregateService = mock(SeriesRuntimeAggregateService.class);
    private final TmdbClient tmdbClient = mock(TmdbClient.class);
    private final UUID watcherId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        trackedContentStateRepository.deleteAll();
        contentRepository.deleteAll();
        movie = contentRepository.saveAndFlush(Content.builder()
                .tmdbId("603")
                .type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        trackedContentStateRepository.saveAndFlush(TrackedContentState.builder()
                .content(movie)
                .lastKnownStatus("Planned")
                .lastCheckedAt(LocalDateTime.now().minusDays(1))
                .build());
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(diaryEntryRepository.findInProgressSeriesTmdbIds(null)).thenReturn(List.of());
        when(watchlistEntryRepository.findUserIdsByContentId(movie.getId())).thenReturn(List.of(watcherId));
        when(userRepository.getReferenceById(watcherId)).thenReturn(User.builder().id(watcherId).build());
    }

    @Test
    void separateInstancesSerializeTransitionAndCreateOneNotification() throws Exception {
        CyclicBarrier bothFetched = new CyclicBarrier(2);
        CountDownLatch firstNotificationEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstNotification = new CountDownLatch(1);
        AtomicInteger notificationWrites = new AtomicInteger();
        AtomicInteger workerSequence = new AtomicInteger();
        AtomicReference<String> firstNotificationWorker = new AtomicReference<>();
        Map<String, Long> backendPids = new ConcurrentHashMap<>();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        NewTransactionExecutor transactionExecutor = transactionExecutor(transaction, backendPids);
        ContentTrackingServiceImpl firstInstance = service(transactionExecutor);
        ContentTrackingServiceImpl secondInstance = service(transactionExecutor);

        when(tmdbClient.getMovieDetails("603")).thenAnswer(invocation -> {
            bothFetched.await(5, TimeUnit.SECONDS);
            return Optional.of(new TmdbMovieDetails("603", "2026-08-29", "Released"));
        });
        doAnswer(invocation -> {
            if (notificationWrites.incrementAndGet() == 1) {
                firstNotificationWorker.set(Thread.currentThread().getName());
                firstNotificationEntered.countDown();
                assertThat(releaseFirstNotification.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.getArgument(0);
        }).when(notificationRepository).saveAll(any());

        ExecutorService workers = Executors.newFixedThreadPool(2,
                task -> new Thread(task, "content-tracker-" + workerSequence.incrementAndGet()));
        try {
            Future<?> first = workers.submit(firstInstance::trackContentChanges);
            Future<?> second = workers.submit(secondInstance::trackContentChanges);
            assertThat(firstNotificationEntered.await(5, TimeUnit.SECONDS)).isTrue();
            String secondWorker = "content-tracker-" + (firstNotificationWorker.get().endsWith("1") ? "2" : "1");
            assertThat(awaitWaitingForAdvisoryLock(backendPids, secondWorker)).isTrue();

            releaseFirstNotification.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirstNotification.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(notificationWrites).hasValue(1);
        assertThat(trackedContentStateRepository.findByContentId(movie.getId()))
                .get().extracting(TrackedContentState::getLastKnownStatus).isEqualTo("Released");
    }

    private ContentTrackingServiceImpl service(NewTransactionExecutor transactionExecutor) {
        return new ContentTrackingServiceImpl(
                watchlistEntryRepository,
                diaryEntryRepository,
                trackedContentStateRepository,
                notificationRepository,
                userRepository,
                contentService,
                contentRepository,
                tmdbClient,
                new ContentChangeDetector(),
                runtimeAggregateService,
                transactionExecutor,
                advisoryLock,
                refreshGenerationSequence);
    }

    private NewTransactionExecutor transactionExecutor(
            TransactionTemplate transaction, Map<String, Long> backendPids) {
        return new NewTransactionExecutor() {
            @Override
            public <T> T runInNewTransaction(Supplier<T> action) {
                return transaction.execute(status -> {
                    backendPids.put(Thread.currentThread().getName(),
                            jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class));
                    return action.get();
                });
            }
        };
    }

    private boolean awaitWaitingForAdvisoryLock(Map<String, Long> backendPids, String workerName)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            Long backendPid = backendPids.get(workerName);
            if (backendPid != null) {
                Boolean waiting = jdbcTemplate.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_locks
                            WHERE pid = ? AND locktype = 'advisory' AND granted = false
                        )
                        """, Boolean.class, backendPid);
                if (Boolean.TRUE.equals(waiting)) {
                    return true;
                }
            }
            Thread.onSpinWait();
        }
        return false;
    }
}
