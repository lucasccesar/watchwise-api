package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.repository.TrackedContentStateRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentTrackingJobTest {

    @Mock private WatchlistEntryRepository watchlistEntryRepository;
    @Mock private DiaryEntryRepository diaryEntryRepository;
    @Mock private TrackedContentStateRepository trackedContentStateRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private TmdbClient tmdbClient;
    @Mock private ContentChangeDetector contentChangeDetector;
    @Mock private NewTransactionExecutor newTransactionExecutor;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private ContentTrackingJob contentTrackingJob;

    @Captor
    private ArgumentCaptor<Notification> notificationCaptor;

    private Content movie;
    private UUID watchingUserId;

    @BeforeEach
    void setUp() {
        movie = Content.builder().id(UUID.randomUUID()).tmdbId("603").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        watchingUserId = UUID.randomUUID();

        lenient().when(newTransactionExecutor.runInNewTransaction(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        lenient().when(userRepository.getReferenceById(watchingUserId))
                .thenReturn(User.builder().id(watchingUserId).build());
    }

    @Test
    @DisplayName("[run] Should Create A Notification For Every Watching User - When A Movie Change Is Detected")
    void shouldCreateANotificationForEveryWatchingUserWhenAMovieChangeIsDetected() {
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(diaryEntryRepository.findDistinctInProgressSeriesContent()).thenReturn(List.of());
        when(trackedContentStateRepository.findByContentId(movie.getId())).thenReturn(Optional.empty());
        TmdbMovieDetails details = new TmdbMovieDetails("603", "2026-08-29", "Released");
        when(tmdbClient.getMovieDetails("603")).thenReturn(Optional.of(details));
        when(contentChangeDetector.detectMovieChange(any(), any(), any()))
                .thenReturn(Optional.of(new ContentChangeEvent(NotificationType.RELEASE, null, null, null)));
        when(watchlistEntryRepository.findUserIdsByContentId(movie.getId())).thenReturn(List.of(watchingUserId));

        contentTrackingJob.run();

        verify(notificationRepository).save(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().getUser().getId()).isEqualTo(watchingUserId);
        assertThat(notificationCaptor.getValue().getType()).isEqualTo(NotificationType.RELEASE);
        assertThat(notificationCaptor.getValue().getContent()).isEqualTo(movie);
    }

    @Test
    @DisplayName("[run] Should Update TrackedContentState - When A Change Is Detected")
    void shouldUpdateTrackedContentStateWhenAChangeIsDetected() {
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(diaryEntryRepository.findDistinctInProgressSeriesContent()).thenReturn(List.of());
        when(trackedContentStateRepository.findByContentId(movie.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getMovieDetails("603")).thenReturn(Optional.of(new TmdbMovieDetails("603", "2026-08-29", "Released")));
        when(contentChangeDetector.detectMovieChange(any(), any(), any()))
                .thenReturn(Optional.of(new ContentChangeEvent(NotificationType.RELEASE, null, null, null)));
        when(watchlistEntryRepository.findUserIdsByContentId(movie.getId())).thenReturn(List.of(watchingUserId));

        contentTrackingJob.run();

        ArgumentCaptor<TrackedContentState> stateCaptor = ArgumentCaptor.forClass(TrackedContentState.class);
        verify(trackedContentStateRepository).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getLastKnownStatus()).isEqualTo("Released");
        assertThat(stateCaptor.getValue().getLastKnownReleaseDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 29));
    }

    @Test
    @DisplayName("[run] Should Not Create A Notification - When No Change Is Detected")
    void shouldNotCreateANotificationWhenNoChangeIsDetected() {
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(diaryEntryRepository.findDistinctInProgressSeriesContent()).thenReturn(List.of());
        when(trackedContentStateRepository.findByContentId(movie.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getMovieDetails("603")).thenReturn(Optional.of(new TmdbMovieDetails("603", "", "Planned")));
        when(contentChangeDetector.detectMovieChange(any(), any(), any())).thenReturn(Optional.empty());

        contentTrackingJob.run();

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("[run] Should Skip The Item And Continue - When TMDB Returns Nothing For It")
    void shouldSkipTheItemAndContinueWhenTmdbReturnsNothingForIt() {
        Content secondMovie = Content.builder().id(UUID.randomUUID()).tmdbId("999").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie, secondMovie));
        when(diaryEntryRepository.findDistinctInProgressSeriesContent()).thenReturn(List.of());
        when(tmdbClient.getMovieDetails("603")).thenReturn(Optional.empty());
        when(trackedContentStateRepository.findByContentId(secondMovie.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getMovieDetails("999")).thenReturn(Optional.of(new TmdbMovieDetails("999", "", "Planned")));
        when(contentChangeDetector.detectMovieChange(any(), any(), any())).thenReturn(Optional.empty());

        contentTrackingJob.run();

        verify(trackedContentStateRepository, never()).findByContentId(movie.getId());
        verify(tmdbClient).getMovieDetails("999");
    }

    @Test
    @DisplayName("[run] Should Only Call TMDB Once - When The Same Series Is Both Watchlisted And In Progress")
    void shouldOnlyCallTmdbOnceWhenTheSameSeriesIsBothWatchlistedAndInProgress() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(series));
        when(diaryEntryRepository.findDistinctInProgressSeriesContent()).thenReturn(List.of(series));
        when(trackedContentStateRepository.findByContentId(series.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getTvDetails("1399")).thenReturn(Optional.of(new TmdbTvDetails("1399", "Returning Series", null)));
        when(contentChangeDetector.detectTvChange(any(), any(), any())).thenReturn(List.of());

        contentTrackingJob.run();

        verify(tmdbClient, times(1)).getTvDetails("1399");
        verify(trackedContentStateRepository, times(1)).findByContentId(series.getId());
    }
}
