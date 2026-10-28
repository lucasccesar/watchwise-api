package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbNextEpisode;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.common.transaction.RefreshGenerationSequence;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeAggregateService;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.repository.TrackedContentStateRepository;
import com.watchwise.watchwise_api.notification.tracking.ContentChangeDetector;
import com.watchwise.watchwise_api.notification.tracking.ContentChangeEvent;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class ContentTrackingServiceImplTest {

    @Mock private WatchlistEntryRepository watchlistEntryRepository;
    @Mock private DiaryEntryRepository diaryEntryRepository;
    @Mock private TrackedContentStateRepository trackedContentStateRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private ContentService contentService;
    @Mock private ContentRepository contentRepository;
    @Mock private SeriesRuntimeAggregateService seriesRuntimeAggregateService;
    @Mock private TmdbClient tmdbClient;
    @Mock private ContentChangeDetector contentChangeDetector;
    @Mock private NewTransactionExecutor newTransactionExecutor;
    @Mock private AdvisoryLock advisoryLock;
    @Mock private RefreshGenerationSequence refreshGenerationSequence;

    @InjectMocks
    private ContentTrackingServiceImpl contentTrackingService;

    @Captor
    private ArgumentCaptor<List<Notification>> notificationsCaptor;

    private Content movie;
    private UUID watchingUserId;

    @BeforeEach
    void setUp() {
        movie = Content.builder().id(UUID.randomUUID()).tmdbId("603").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        watchingUserId = UUID.randomUUID();

        lenient().when(newTransactionExecutor.runInNewTransaction(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        AtomicLong generations = new AtomicLong(1);
        lenient().when(refreshGenerationSequence.next()).thenAnswer(invocation -> generations.getAndIncrement());
        lenient().when(userRepository.getReferenceById(watchingUserId))
                .thenReturn(User.builder().id(watchingUserId).build());
        lenient().when(diaryEntryRepository.findInProgressSeriesTmdbIds(null)).thenReturn(List.of());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Create A Notification For Every Watching User - When A Movie Change Is Detected")
    void shouldCreateANotificationForEveryWatchingUserWhenAMovieChangeIsDetected() {
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(trackedContentStateRepository.findByContentId(movie.getId())).thenReturn(Optional.empty());
        TmdbMovieDetails details = new TmdbMovieDetails("603", "2026-08-29", "Released");
        when(tmdbClient.getMovieDetails("603")).thenReturn(Optional.of(details));
        when(contentChangeDetector.detectMovieChange(any(), any(), any()))
                .thenReturn(Optional.of(new ContentChangeEvent(NotificationType.RELEASE, null, null, null)));
        when(watchlistEntryRepository.findUserIdsByContentId(movie.getId())).thenReturn(List.of(watchingUserId));

        contentTrackingService.trackContentChanges();

        verify(notificationRepository).saveAll(notificationsCaptor.capture());
        assertThat(notificationsCaptor.getValue()).hasSize(1);
        Notification notification = notificationsCaptor.getValue().get(0);
        assertThat(notification.getUser().getId()).isEqualTo(watchingUserId);
        assertThat(notification.getType()).isEqualTo(NotificationType.RELEASE);
        assertThat(notification.getContent()).isEqualTo(movie);
    }

    @Test
    void concurrentInstancesEmitOnlyOneTransitionNotification() throws Exception {
        TrackedContentState initial = TrackedContentState.builder()
                .content(movie)
                .lastKnownStatus("Planned")
                .lastCheckedAt(LocalDateTime.now().minusDays(1))
                .build();
        AtomicReference<TrackedContentState> current = new AtomicReference<>(initial);
        CyclicBarrier tmdbBarrier = new CyclicBarrier(2);
        CyclicBarrier unlockedReadBarrier = new CyclicBarrier(2);
        ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
        ThreadLocal<ReentrantLock> heldLock = new ThreadLocal<>();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(movie.getId()))
                .thenAnswer(invocation -> Optional.ofNullable(current.get()).map(TrackedContentState::getLastKnownStatus));
        when(trackedContentStateRepository.findByContentId(movie.getId())).thenAnswer(invocation -> {
            TrackedContentState snapshot = current.get();
            if (heldLock.get() == null) {
                unlockedReadBarrier.await(10, TimeUnit.SECONDS);
            }
            return Optional.ofNullable(heldLock.get() == null ? snapshot : current.get());
        });
        doAnswer(invocation -> {
            locks.computeIfAbsent(invocation.getArgument(0), ignored -> new ReentrantLock()).lock();
            heldLock.set(locks.get(invocation.getArgument(0)));
            return null;
        }).when(advisoryLock).lock(any());
        doAnswer(invocation -> {
            try {
                return ((Supplier<?>) invocation.getArgument(0)).get();
            } finally {
                ReentrantLock lock = heldLock.get();
                if (lock != null) {
                    heldLock.remove();
                    lock.unlock();
                }
            }
        }).when(newTransactionExecutor).runInNewTransaction(any());
        when(tmdbClient.getMovieDetails("603")).thenAnswer(invocation -> {
            tmdbBarrier.await(10, TimeUnit.SECONDS);
            return Optional.of(new TmdbMovieDetails("603", "2026-08-29", "Released"));
        });
        when(contentChangeDetector.detectMovieChange(any(), any(), any())).thenAnswer(invocation ->
                new com.watchwise.watchwise_api.notification.tracking.ContentChangeDetector().detectMovieChange(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(watchlistEntryRepository.findUserIdsByContentId(movie.getId())).thenReturn(List.of(watchingUserId));
        doAnswer(invocation -> {
            current.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        }).when(trackedContentStateRepository).save(any());

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(contentTrackingService::trackContentChanges);
            Future<?> second = workers.submit(contentTrackingService::trackContentChanges);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        verify(notificationRepository, times(1)).saveAll(notificationsCaptor.capture());
        assertThat(notificationsCaptor.getAllValues()).hasSize(1);
        assertThat(notificationsCaptor.getValue()).hasSize(1);
        assertThat(current.get().getLastKnownStatus()).isEqualTo("Released");
    }

    @Test
    void olderFetchFinishingLastCannotReplaceNewerTrackedState() throws Exception {
        TrackedContentState initial = TrackedContentState.builder()
                .content(movie)
                .lastKnownStatus("Planned")
                .lastCheckedAt(LocalDateTime.now().minusDays(1))
                .build();
        AtomicReference<TrackedContentState> current = new AtomicReference<>(initial);
        CountDownLatch olderFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseOlderFetch = new CountDownLatch(1);
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(movie.getId())).thenAnswer(invocation -> {
            return Optional.of("Planned");
        });
        when(trackedContentStateRepository.findByContentId(movie.getId()))
                .thenAnswer(invocation -> Optional.ofNullable(current.get()));
        doAnswer(invocation -> {
            current.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        }).when(trackedContentStateRepository).save(any());
        when(tmdbClient.getMovieDetails("603")).thenAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("older-content-observation")) {
                olderFetchStarted.countDown();
                assertThat(releaseOlderFetch.await(5, TimeUnit.SECONDS)).isTrue();
                return Optional.of(new TmdbMovieDetails("603", "2026-07-01", "Post Production"));
            }
            return Optional.of(new TmdbMovieDetails("603", "2026-08-29", "Released"));
        });
        when(contentChangeDetector.detectMovieChange(any(), any(), any())).thenAnswer(invocation ->
                new ContentChangeDetector().detectMovieChange(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));

        ContentTrackingServiceImpl anotherInstance = new ContentTrackingServiceImpl(
                watchlistEntryRepository, diaryEntryRepository, trackedContentStateRepository,
                notificationRepository, userRepository, contentService, contentRepository,
                tmdbClient, contentChangeDetector, seriesRuntimeAggregateService, newTransactionExecutor,
                advisoryLock, refreshGenerationSequence);
        ExecutorService olderWorker = Executors.newSingleThreadExecutor(
                task -> new Thread(task, "older-content-observation"));
        ExecutorService newerWorker = Executors.newSingleThreadExecutor(
                task -> new Thread(task, "newer-content-observation"));
        try {
            Future<?> older = olderWorker.submit(contentTrackingService::trackContentChanges);
            assertThat(olderFetchStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> newer = newerWorker.submit(anotherInstance::trackContentChanges);

            newer.get(5, TimeUnit.SECONDS);
            assertThat(current.get().getLastKnownStatus()).isEqualTo("Released");

            releaseOlderFetch.countDown();
            older.get(5, TimeUnit.SECONDS);
        } finally {
            releaseOlderFetch.countDown();
            olderWorker.shutdownNow();
            newerWorker.shutdownNow();
        }

        assertThat(current.get().getLastKnownStatus()).isEqualTo("Released");
        assertThat(current.get().getLastKnownReleaseDate()).isEqualTo(LocalDate.parse("2026-08-29"));
    }

    @Test
    @DisplayName("[trackContentChanges] Should Update TrackedContentState - When A Change Is Detected")
    void shouldUpdateTrackedContentStateWhenAChangeIsDetected() {
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(trackedContentStateRepository.findByContentId(movie.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getMovieDetails("603")).thenReturn(Optional.of(new TmdbMovieDetails("603", "2026-08-29", "Released")));
        when(contentChangeDetector.detectMovieChange(any(), any(), any()))
                .thenReturn(Optional.of(new ContentChangeEvent(NotificationType.RELEASE, null, null, null)));
        when(watchlistEntryRepository.findUserIdsByContentId(movie.getId())).thenReturn(List.of(watchingUserId));

        contentTrackingService.trackContentChanges();

        ArgumentCaptor<TrackedContentState> stateCaptor = ArgumentCaptor.forClass(TrackedContentState.class);
        verify(trackedContentStateRepository).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getLastKnownStatus()).isEqualTo("Released");
        assertThat(stateCaptor.getValue().getLastKnownReleaseDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 29));
    }

    @Test
    @DisplayName("[trackContentChanges] Should Not Create A Notification - When No Change Is Detected")
    void shouldNotCreateANotificationWhenNoChangeIsDetected() {
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(trackedContentStateRepository.findByContentId(movie.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getMovieDetails("603")).thenReturn(Optional.of(new TmdbMovieDetails("603", "", "Planned")));
        when(contentChangeDetector.detectMovieChange(any(), any(), any())).thenReturn(Optional.empty());

        contentTrackingService.trackContentChanges();

        verify(notificationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Skip The Item And Continue - When TMDB Returns Nothing For It")
    void shouldSkipTheItemAndContinueWhenTmdbReturnsNothingForIt() {
        Content secondMovie = Content.builder().id(UUID.randomUUID()).tmdbId("999").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie, secondMovie));
        when(tmdbClient.getMovieDetails("603")).thenReturn(Optional.empty());
        when(trackedContentStateRepository.findByContentId(secondMovie.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getMovieDetails("999")).thenReturn(Optional.of(new TmdbMovieDetails("999", "", "Planned")));
        when(contentChangeDetector.detectMovieChange(any(), any(), any())).thenReturn(Optional.empty());

        contentTrackingService.trackContentChanges();

        verify(trackedContentStateRepository, never()).findByContentId(movie.getId());
        verify(tmdbClient).getMovieDetails("999");
    }

    @Test
    @DisplayName("[trackContentChanges] Should Only Call TMDB Once - When The Same Series Is Both Watchlisted And In Progress")
    void shouldOnlyCallTmdbOnceWhenTheSameSeriesIsBothWatchlistedAndInProgress() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(diaryEntryRepository.findInProgressSeriesTmdbIds(null)).thenReturn(List.of("1399"));
        ContentRefDTO contentRef = new ContentRefDTO(series.getId(), "1399", ContentType.SERIES,
                null, null, null, null, null, null, null);
        when(contentService.getOrCreateReference(any())).thenReturn(contentRef);
        when(contentRepository.findById(series.getId())).thenReturn(Optional.of(series));
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(new TmdbTvDetails("1399", "Returning Series", null, null)));
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of());

        contentTrackingService.trackContentChanges();

        verify(tmdbClient, times(1)).getTvDetails("1399");
        verify(trackedContentStateRepository, times(1)).findByContentId(series.getId());
    }

    @Test
    void passesTheParentRefreshGenerationToEveryNewEpisodeFromOneResponse() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .totalRuntimeMinutes(4_800).runtimeMinutes(48).runtimeMinutesEpisodeCount(100)
                .runtimeReportedEpisodeCount(100).runtimeAggregateVerifiedAt(LocalDateTime.now().minusHours(1))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(new TmdbTvDetails("1399", "Returning Series",
                null, List.of(new TmdbSeasonSummary(1, null, null, null, 100, null),
                        new TmdbSeasonSummary(2, null, null, null, 2, null)))));
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of(
                new ContentChangeEvent(NotificationType.NEW_EPISODE, null, 2, 1),
                new ContentChangeEvent(NotificationType.NEW_EPISODE, null, 2, 2)));
        when(refreshGenerationSequence.next()).thenReturn(73L);

        contentTrackingService.trackContentChanges();

        ArgumentCaptor<Long> generations = ArgumentCaptor.forClass(Long.class);
        verify(seriesRuntimeAggregateService, times(2)).incrementForNewEpisode(
                eq(series), any(), any(), any(), generations.capture());
        assertThat(generations.getAllValues()).containsExactly(73L, 73L);
    }

    @Test
    @DisplayName("[trackContentChanges] Should Save Next Episode Air Date As Null - When TMDB Returns A Blank Air Date")
    void shouldSaveNextEpisodeAirDateAsNullWhenTmdbReturnsABlankAirDate() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.empty());
        TmdbTvDetails details = new TmdbTvDetails("1399", "Returning Series", new TmdbNextEpisode("", 6, 5), null);
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(details));
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of());

        contentTrackingService.trackContentChanges();

        ArgumentCaptor<TrackedContentState> stateCaptor = ArgumentCaptor.forClass(TrackedContentState.class);
        verify(trackedContentStateRepository).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getNextEpisodeAirDate()).isNull();
        assertThat(stateCaptor.getValue().getNextEpisodeSeasonNumber()).isEqualTo(6);
        assertThat(stateCaptor.getValue().getNextEpisodeNumber()).isEqualTo(5);
    }

    @Test
    @DisplayName("[trackContentChanges] Should Save Next Episode Air Date As Null - When TMDB Returns A Null Air Date")
    void shouldSaveNextEpisodeAirDateAsNullWhenTmdbReturnsANullAirDate() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.empty());
        TmdbTvDetails details = new TmdbTvDetails("1399", "Returning Series", new TmdbNextEpisode(null, 6, 5), null);
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(details));
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of());

        contentTrackingService.trackContentChanges();

        ArgumentCaptor<TrackedContentState> stateCaptor = ArgumentCaptor.forClass(TrackedContentState.class);
        verify(trackedContentStateRepository).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getNextEpisodeAirDate()).isNull();
    }

    @Test
    @DisplayName("[trackContentChanges] Should Track And Create A SERIES Content Row - When A Series Only Has EPISODE Diary Entries")
    void shouldTrackAndCreateASeriesContentRowWhenASeriesOnlyHasEpisodeDiaryEntries() {
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of());
        when(diaryEntryRepository.findInProgressSeriesTmdbIds(null)).thenReturn(List.of("1399"));
        Content seriesContent = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        ContentRefDTO contentRef = new ContentRefDTO(seriesContent.getId(), "1399", ContentType.SERIES,
                null, null, null, null, null, null, null);
        when(contentService.getOrCreateReference(any())).thenReturn(contentRef);
        when(contentRepository.findById(seriesContent.getId())).thenReturn(Optional.of(seriesContent));
        when(trackedContentStateRepository.findByContentId(seriesContent.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(new TmdbTvDetails("1399", "Returning Series", null, null)));
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of());

        contentTrackingService.trackContentChanges();

        verify(contentService).getOrCreateReference(
                argThat(dto -> "1399".equals(dto.tmdbId()) && dto.type() == ContentType.SERIES));
        verify(tmdbClient).getTvDetails("1399");
    }

    @Test
    @DisplayName("[trackContentChanges] Should Not Call TMDB - When Movie Last Known Status Is Released")
    void shouldNotCallTmdbWhenMovieLastKnownStatusIsReleased() {
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(movie.getId())).thenReturn(Optional.of("Released"));

        contentTrackingService.trackContentChanges();

        verify(tmdbClient, never()).getMovieDetails(any());
        verify(trackedContentStateRepository, never()).findByContentId(any());
        verify(notificationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Not Call TMDB - When Movie Last Known Status Is Canceled")
    void shouldNotCallTmdbWhenMovieLastKnownStatusIsCanceled() {
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(movie.getId())).thenReturn(Optional.of("Canceled"));

        contentTrackingService.trackContentChanges();

        verify(tmdbClient, never()).getMovieDetails(any());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Not Call TMDB - When Series Last Known Status Is Ended")
    void shouldNotCallTmdbWhenSeriesLastKnownStatusIsEnded() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId())).thenReturn(Optional.of("Ended"));

        contentTrackingService.trackContentChanges();

        verify(tmdbClient, never()).getTvDetails(any());
        verify(trackedContentStateRepository, never()).findByContentId(any());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Not Call TMDB - When Series Last Known Status Is Canceled")
    void shouldNotCallTmdbWhenSeriesLastKnownStatusIsCanceled() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId())).thenReturn(Optional.of("Canceled"));

        contentTrackingService.trackContentChanges();

        verify(tmdbClient, never()).getTvDetails(any());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Still Call TMDB - When No Previous Tracked State Exists")
    void shouldStillCallTmdbWhenNoPreviousTrackedStateExists() {
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(movie.getId())).thenReturn(Optional.empty());
        when(trackedContentStateRepository.findByContentId(movie.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getMovieDetails("603")).thenReturn(Optional.of(new TmdbMovieDetails("603", "", "Planned")));
        when(contentChangeDetector.detectMovieChange(any(), any(), any())).thenReturn(Optional.empty());

        contentTrackingService.trackContentChanges();

        verify(tmdbClient).getMovieDetails("603");
    }

    @Test
    @DisplayName("[trackContentChanges] Should Still Call TMDB - When Series Last Known Status Is Returning Series")
    void shouldStillCallTmdbWhenSeriesLastKnownStatusIsReturningSeries() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId())).thenReturn(Optional.of("Returning Series"));
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(new TmdbTvDetails("1399", "Returning Series", null, null)));
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of());

        contentTrackingService.trackContentChanges();

        verify(tmdbClient).getTvDetails("1399");
    }

    @Test
    @DisplayName("[trackContentChanges] Should Increment Stored Runtime - When NEW_EPISODE Event Occurs And Baseline Exists")
    void shouldIncrementStoredRuntimeWhenNewEpisodeEventOccursAndBaselineExists() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .totalRuntimeMinutes(500).runtimeMinutesEpisodeCount(10).runtimeReportedEpisodeCount(10)
                .runtimeAggregateVerifiedAt(LocalDateTime.now().minusHours(1))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId())).thenReturn(Optional.of("Returning Series"));
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.empty());
        TmdbTvDetails fresh = new TmdbTvDetails("1399", "Returning Series", null,
                List.of(new TmdbSeasonSummary(6, null, null, null, 11, null)));
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(fresh));
        when(contentChangeDetector.detectTvChange(any(), any(), any()))
                .thenReturn(List.of(new ContentChangeEvent(NotificationType.NEW_EPISODE, LocalDate.now(), 6, 3)));

        contentTrackingService.trackContentChanges();

        verify(seriesRuntimeAggregateService).incrementForNewEpisode(
                eq(series), eq(6), eq(3), eq(11), anyLong());
        verify(seriesRuntimeAggregateService, never()).reconcileBeforeFreezing(any(), any(), anyLong());
        verify(seriesRuntimeAggregateService, never()).initializeIfMissing(any(), any(), anyLong());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Increment Runtime Once - When The Detector Returns Duplicate NEW_EPISODE Events")
    void shouldIncrementRuntimeOnceWhenTheDetectorReturnsDuplicateNewEpisodeEvents() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .totalRuntimeMinutes(500).runtimeMinutesEpisodeCount(10).runtimeReportedEpisodeCount(10)
                .runtimeAggregateVerifiedAt(LocalDateTime.now().minusHours(1))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId())).thenReturn(Optional.of("Returning Series"));
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.empty());
        TmdbTvDetails fresh = new TmdbTvDetails("1399", "Returning Series", null,
                List.of(new TmdbSeasonSummary(6, null, null, null, 11, null)));
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(fresh));
        ContentChangeEvent event = new ContentChangeEvent(NotificationType.NEW_EPISODE, LocalDate.now(), 6, 3);
        ContentChangeEvent announcedDate = new ContentChangeEvent(
                NotificationType.ANNOUNCED_DATE, LocalDate.now().plusDays(1), 7, null);
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of(event, event, announcedDate));
        when(diaryEntryRepository.findUserIdsWatchingSeries("1399")).thenReturn(List.of(watchingUserId));
        when(watchlistEntryRepository.findUserIdsByContentId(series.getId())).thenReturn(List.of(watchingUserId));

        contentTrackingService.trackContentChanges();

        verify(seriesRuntimeAggregateService, times(1)).incrementForNewEpisode(
                eq(series), eq(6), eq(3), eq(11), anyLong());
        verify(notificationRepository, times(2)).saveAll(notificationsCaptor.capture());
        List<List<Notification>> notifications = notificationsCaptor.getAllValues();
        assertThat(notifications).allSatisfy(batch -> assertThat(batch).hasSize(1));
        assertThat(notifications.get(0).get(0).getType()).isEqualTo(NotificationType.NEW_EPISODE);
        assertThat(notifications.get(1).get(0).getType()).isEqualTo(NotificationType.ANNOUNCED_DATE);
    }

    @Test
    @DisplayName("[trackContentChanges] Should Persist Tracking State - When Non-Terminal Runtime Synchronization Fails")
    void shouldPersistTrackingStateWhenNonTerminalRuntimeSynchronizationFails() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .totalRuntimeMinutes(500).runtimeMinutesEpisodeCount(10).runtimeReportedEpisodeCount(10)
                .runtimeAggregateVerifiedAt(LocalDateTime.now().minusHours(1))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId()))
                .thenReturn(Optional.of("Returning Series"));
        TrackedContentState previous = TrackedContentState.builder()
                .content(series).lastKnownStatus("Returning Series").build();
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.of(previous));
        TmdbTvDetails fresh = new TmdbTvDetails("1399", "Returning Series", null,
                List.of(new TmdbSeasonSummary(6, null, null, null, 11, null)));
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(fresh));
        ContentChangeEvent event = new ContentChangeEvent(NotificationType.NEW_EPISODE, LocalDate.now(), 6, 3);
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of(event));
        when(diaryEntryRepository.findUserIdsWatchingSeries("1399")).thenReturn(List.of(watchingUserId));
        doThrow(new IllegalStateException("runtime unavailable"))
                .when(seriesRuntimeAggregateService)
                .incrementForNewEpisode(eq(series), eq(6), eq(3), eq(11), anyLong());

        contentTrackingService.trackContentChanges();

        verify(notificationRepository).saveAll(any());
        ArgumentCaptor<TrackedContentState> stateCaptor = ArgumentCaptor.forClass(TrackedContentState.class);
        verify(trackedContentStateRepository).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getLastKnownStatus()).isEqualTo("Returning Series");
    }

    @Test
    @DisplayName("[trackContentChanges] Should Reconcile Stale Runtime - When A Failed Increment Is Followed By A Run Without An Event")
    void shouldReconcileStaleRuntimeWhenAFailedIncrementIsFollowedByARunWithoutAnEvent() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .totalRuntimeMinutes(500).runtimeMinutesEpisodeCount(10).runtimeReportedEpisodeCount(10)
                .runtimeAggregateVerifiedAt(LocalDateTime.now().minusHours(1))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series), List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId()))
                .thenReturn(Optional.of("Returning Series"), Optional.of("Returning Series"));
        TrackedContentState previous = TrackedContentState.builder()
                .content(series).lastKnownStatus("Returning Series").build();
        when(trackedContentStateRepository.findByContentId(series.getId()))
                .thenReturn(Optional.of(previous), Optional.of(previous));
        TmdbTvDetails fresh = new TmdbTvDetails("1399", "Returning Series", null,
                List.of(new TmdbSeasonSummary(6, null, null, null, 11, null)));
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(fresh), Optional.of(fresh));
        ContentChangeEvent event = new ContentChangeEvent(NotificationType.NEW_EPISODE, LocalDate.now(), 6, 3);
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of(event), List.of());
        doThrow(new IllegalStateException("runtime unavailable"))
                .when(seriesRuntimeAggregateService)
                .incrementForNewEpisode(eq(series), eq(6), eq(3), eq(11), anyLong());

        contentTrackingService.trackContentChanges();
        contentTrackingService.trackContentChanges();

        verify(seriesRuntimeAggregateService).incrementForNewEpisode(
                eq(series), eq(6), eq(3), eq(11), anyLong());
        verify(seriesRuntimeAggregateService).reconcileBeforeFreezing(eq(series), eq(fresh), anyLong());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Retry Runtime Initialization - When An Incomplete Baseline Remains After A Failed Attempt")
    void shouldRetryRuntimeInitializationWhenAnIncompleteBaselineRemainsAfterAFailedAttempt() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .totalRuntimeMinutes(500).runtimeMinutesEpisodeCount(10).runtimeReportedEpisodeCount(10)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series), List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId()))
                .thenReturn(Optional.of("Returning Series"), Optional.of("Returning Series"));
        TrackedContentState previous = TrackedContentState.builder()
                .content(series).lastKnownStatus("Returning Series").build();
        when(trackedContentStateRepository.findByContentId(series.getId()))
                .thenReturn(Optional.of(previous), Optional.of(previous));
        TmdbTvDetails fresh = new TmdbTvDetails("1399", "Returning Series", null, null);
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(fresh), Optional.of(fresh));
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of(), List.of());
        doThrow(new IllegalStateException("runtime unavailable"))
                .doNothing().when(seriesRuntimeAggregateService)
                .initializeIfMissing(eq(series), eq(fresh), anyLong());

        contentTrackingService.trackContentChanges();
        contentTrackingService.trackContentChanges();

        verify(seriesRuntimeAggregateService, times(2))
                .initializeIfMissing(eq(series), eq(fresh), anyLong());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Initialize Runtime - When NEW_EPISODE Event Occurs But No Baseline Exists Yet")
    void shouldInitializeRuntimeWhenNewEpisodeEventOccursButNoBaselineExistsYet() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId())).thenReturn(Optional.of("Returning Series"));
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.empty());
        TmdbTvDetails fresh = new TmdbTvDetails("1399", "Returning Series", null, null);
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(fresh));
        when(contentChangeDetector.detectTvChange(any(), any(), any()))
                .thenReturn(List.of(new ContentChangeEvent(NotificationType.NEW_EPISODE, LocalDate.now(), 6, 3)));

        contentTrackingService.trackContentChanges();

        verify(seriesRuntimeAggregateService).initializeIfMissing(eq(series), eq(fresh), anyLong());
        verify(seriesRuntimeAggregateService, never()).incrementForNewEpisode(any(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Recalculate Runtime From Scratch Before Freezing - When Series Transitions To Ended")
    void shouldRecalculateRuntimeFromScratchBeforeFreezingWhenSeriesTransitionsToEnded() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .totalRuntimeMinutes(300).runtimeMinutesEpisodeCount(6).runtimeReportedEpisodeCount(6)
                .runtimeAggregateVerifiedAt(LocalDateTime.now().minusHours(1))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId())).thenReturn(Optional.of("Returning Series"));
        TrackedContentState previous = TrackedContentState.builder().content(series).lastKnownStatus("Returning Series").build();
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.of(previous));
        TmdbTvDetails fresh = new TmdbTvDetails("1399", "Ended", null,
                List.of(new TmdbSeasonSummary(1, null, null, "2020-01-01", 1, null),
                        new TmdbSeasonSummary(2, null, null, "2021-01-01", 1, null)));
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(fresh));
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of());

        contentTrackingService.trackContentChanges();

        verify(seriesRuntimeAggregateService).reconcileBeforeFreezing(eq(series), eq(fresh), anyLong());
        verify(seriesRuntimeAggregateService, never()).incrementForNewEpisode(any(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Persist Tracking State - When Terminal Runtime Reconciliation Fails")
    void shouldPersistTrackingStateWhenTerminalRuntimeReconciliationFails() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .totalRuntimeMinutes(300).runtimeMinutesEpisodeCount(6).runtimeReportedEpisodeCount(6)
                .runtimeAggregateVerifiedAt(LocalDateTime.now().minusHours(1))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId()))
                .thenReturn(Optional.of("Returning Series"));
        TrackedContentState previous = TrackedContentState.builder()
                .content(series).lastKnownStatus("Returning Series").build();
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.of(previous));
        TmdbTvDetails fresh = new TmdbTvDetails("1399", "Ended", null,
                List.of(new TmdbSeasonSummary(1, null, null, "2020-01-01", 1, null)));
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(fresh));
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of());
        doThrow(new IllegalStateException("runtime unavailable"))
                .when(seriesRuntimeAggregateService).reconcileBeforeFreezing(eq(series), eq(fresh), anyLong());

        contentTrackingService.trackContentChanges();

        ArgumentCaptor<TrackedContentState> stateCaptor = ArgumentCaptor.forClass(TrackedContentState.class);
        verify(trackedContentStateRepository).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getLastKnownStatus()).isEqualTo("Ended");
    }

    @Test
    @DisplayName("[trackContentChanges] Should Reconcile Runtime - When NEW_EPISODE Event Has Incomplete Coordinates")
    void shouldReconcileRuntimeWhenNewEpisodeEventHasIncompleteCoordinates() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .totalRuntimeMinutes(300).runtimeMinutesEpisodeCount(6).runtimeReportedEpisodeCount(6)
                .runtimeAggregateVerifiedAt(LocalDateTime.now().minusHours(1))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId())).thenReturn(Optional.of("Returning Series"));
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.empty());
        TmdbTvDetails fresh = new TmdbTvDetails("1399", "Returning Series", null,
                List.of(new TmdbSeasonSummary(1, null, null, null, 6, null)));
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(fresh));
        when(contentChangeDetector.detectTvChange(any(), any(), any()))
                .thenReturn(List.of(new ContentChangeEvent(NotificationType.NEW_EPISODE, LocalDate.now(), null, 3)));

        contentTrackingService.trackContentChanges();

        verify(seriesRuntimeAggregateService).reconcileBeforeFreezing(eq(series), eq(fresh), anyLong());
        verify(seriesRuntimeAggregateService, never()).incrementForNewEpisode(any(), any(), any(), any(), anyLong());
        verify(seriesRuntimeAggregateService, never()).initializeIfMissing(any(), any(), anyLong());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Preserve Stored Runtime - When Terminal Reconciliation Finds No Runtime")
    void shouldPreserveStoredRuntimeWhenTerminalReconciliationFindsNoRuntime() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .totalRuntimeMinutes(300).runtimeMinutesEpisodeCount(6).runtimeReportedEpisodeCount(6)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId())).thenReturn(Optional.of("Returning Series"));
        TrackedContentState previous = TrackedContentState.builder().content(series).lastKnownStatus("Returning Series").build();
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.of(previous));
        TmdbTvDetails fresh = new TmdbTvDetails("1399", "Ended", null,
                List.of(new TmdbSeasonSummary(1, null, null, "2020-01-01", 1, null)));
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(fresh));
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of());

        contentTrackingService.trackContentChanges();

        verify(seriesRuntimeAggregateService).reconcileBeforeFreezing(eq(series), eq(fresh), anyLong());
        verify(contentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Initialize Runtime - When Series Stays Returning Series With An Incomplete Baseline")
    void shouldInitializeRuntimeWhenSeriesStaysReturningSeriesWithAnIncompleteBaseline() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .totalRuntimeMinutes(300).runtimeMinutesEpisodeCount(6)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId())).thenReturn(Optional.of("Returning Series"));
        TrackedContentState previous = TrackedContentState.builder().content(series).lastKnownStatus("Returning Series").build();
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.of(previous));
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(new TmdbTvDetails("1399", "Returning Series", null, null)));
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of());

        contentTrackingService.trackContentChanges();

        verify(seriesRuntimeAggregateService).initializeIfMissing(eq(series), any(), anyLong());
        verify(seriesRuntimeAggregateService, never()).reconcileBeforeFreezing(any(), any(), anyLong());
        verify(seriesRuntimeAggregateService, never()).incrementForNewEpisode(any(), any(), any(), any(), anyLong());
        verify(tmdbClient, never()).getSeasonFullDetails(any(), any(), any());
        verify(contentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Skip Runtime Work - When Healthy Baseline And Reported Count Are Unchanged")
    void shouldSkipRuntimeWorkWhenHealthyBaselineAndReportedCountAreUnchanged() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .totalRuntimeMinutes(300).runtimeMinutesEpisodeCount(6).runtimeReportedEpisodeCount(6)
                .runtimeAggregateVerifiedAt(LocalDateTime.now().minusHours(1))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findLastKnownStatusByContentId(series.getId()))
                .thenReturn(Optional.of("Returning Series"));
        TrackedContentState previous = TrackedContentState.builder()
                .content(series).lastKnownStatus("Returning Series").build();
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.of(previous));
        TmdbTvDetails fresh = new TmdbTvDetails("1399", "Returning Series", null,
                List.of(new TmdbSeasonSummary(1, null, null, null, 6, null)));
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(fresh));
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of());

        contentTrackingService.trackContentChanges();

        verify(seriesRuntimeAggregateService, never()).initializeIfMissing(any(), any(), anyLong());
        verify(seriesRuntimeAggregateService, never()).reconcileBeforeFreezing(any(), any(), anyLong());
        verify(seriesRuntimeAggregateService, never()).incrementForNewEpisode(any(), any(), any(), any(), anyLong());
        verify(contentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[reactivateAfterRevival] Should Reactivate Tracking And Notify Watchers - When A Frozen Series Revives")
    void shouldReactivateTrackingAndNotifyWatchersWhenAFrozenSeriesRevives() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES).build();
        TrackedContentState previous = TrackedContentState.builder().content(series).lastKnownStatus("Ended").build();
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.of(previous));
        when(watchlistEntryRepository.findUserIdsByContentId(series.getId())).thenReturn(List.of(watchingUserId));

        contentTrackingService.reactivateAfterRevival(series, "Returning Series");

        ArgumentCaptor<TrackedContentState> stateCaptor = ArgumentCaptor.forClass(TrackedContentState.class);
        verify(trackedContentStateRepository).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getLastKnownStatus()).isEqualTo("Returning Series");
        verify(notificationRepository).saveAll(notificationsCaptor.capture());
        assertThat(notificationsCaptor.getValue()).hasSize(1);
        assertThat(notificationsCaptor.getValue().get(0).getType()).isEqualTo(NotificationType.RENEWED);
    }

    @Test
    @DisplayName("[reactivateAfterRevival] Should Do Nothing - When Fresh Status Is Still Terminal")
    void shouldDoNothingWhenFreshStatusIsStillTerminal() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES).build();

        contentTrackingService.reactivateAfterRevival(series, "Ended");

        verify(trackedContentStateRepository, never()).findLastKnownStatusByContentId(any());
        verify(trackedContentStateRepository, never()).save(any());
    }

    @Test
    @DisplayName("[reactivateAfterRevival] Should Do Nothing - When Previous Status Was Not Terminal")
    void shouldDoNothingWhenPreviousStatusWasNotTerminal() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES).build();
        TrackedContentState previous = TrackedContentState.builder()
                .content(series).lastKnownStatus("Returning Series").build();
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.of(previous));

        contentTrackingService.reactivateAfterRevival(series, "Returning Series");

        verify(trackedContentStateRepository).findByContentId(series.getId());
        verify(trackedContentStateRepository, never()).save(any());
        verify(notificationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("[trackContentChanges] Should Continue Processing Other Content - When Resolving A Diary-Derived Series Reference Fails")
    void shouldContinueProcessingOtherContentWhenResolvingADiaryDerivedSeriesReferenceFails() {
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(diaryEntryRepository.findInProgressSeriesTmdbIds(null)).thenReturn(List.of("1399"));
        when(contentService.getOrCreateReference(any())).thenThrow(new RuntimeException("TMDB unavailable"));
        when(trackedContentStateRepository.findByContentId(movie.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getMovieDetails("603")).thenReturn(Optional.of(new TmdbMovieDetails("603", "", "Planned")));
        when(contentChangeDetector.detectMovieChange(any(), any(), any())).thenReturn(Optional.empty());

        contentTrackingService.trackContentChanges();

        verify(tmdbClient).getMovieDetails("603");
        verify(contentRepository, never()).findById(any());
    }
}
