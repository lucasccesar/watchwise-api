package com.watchwise.watchwise_api.contentreleasedatesnapshot.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.contentreleasedatesnapshot.entity.ContentReleaseDateSnapshot;
import com.watchwise.watchwise_api.contentreleasedatesnapshot.repository.ContentReleaseDateSnapshotRepository;
import com.watchwise.watchwise_api.contentreleasedatesnapshot.service.ContentReleaseDateSnapshotService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentReleaseDateSnapshotServiceImplTest {

    @Mock
    private ContentReleaseDateSnapshotRepository snapshotRepository;

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private NewTransactionExecutor newTransactionExecutor;

    @Mock
    private ExecutorService snapshotRefreshExecutor;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        lenient().when(newTransactionExecutor.runInNewTransaction(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    @InjectMocks
    private ContentReleaseDateSnapshotServiceImpl snapshotService;

    @Test
    void shouldPersistMovieReleaseDateFromAppendedReleaseDatesForOwnerRegion() {
        User owner = User.builder().id(UUID.randomUUID()).preferredRegion("BR").build();
        Content movie = Content.builder().id(UUID.randomUUID()).tmdbId("550").type(ContentType.MOVIE).build();
        WatchlistEntry entry = WatchlistEntry.builder()
                .id(UUID.randomUUID()).user(owner).content(movie).type(ContentType.MOVIE).position(1).build();
        TmdbMovieReleaseDates releaseDates = new TmdbMovieReleaseDates("550", List.of(
                new TmdbRegionReleaseDates("US", List.of(new TmdbMovieReleaseDate(null, null, "2027-01-01", null, 3))),
                new TmdbRegionReleaseDates("BR", List.of(new TmdbMovieReleaseDate(null, null, "2027-02-03", null, 3)))
        ));
        TmdbMovieFullDetails details = new TmdbMovieFullDetails("550", "Fight Club", releaseDates);
        ContentReleaseDateSnapshot saved = ContentReleaseDateSnapshot.builder()
                .tmdbId("550").type(ContentType.MOVIE).region("BR")
                .releaseDate(java.time.LocalDate.of(2027, 2, 3))
                .status(ContentReleaseDateSnapshot.Status.FOUND)
                .lastCheckedAt(LocalDateTime.now()).nextCheckAt(LocalDateTime.now().plusDays(1)).build();

        when(snapshotRepository.findByTypeAndRegionAndTmdbIdIn(eq(ContentType.MOVIE), eq("BR"), eq(List.of("550"))))
                .thenReturn(List.of());
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(details));
        when(snapshotRepository.saveAndFlush(any(ContentReleaseDateSnapshot.class))).thenReturn(saved);

        var result = snapshotService.resolve(owner, List.of(entry));

        assertThat(result.releaseDates()).containsEntry(entry.getId(), java.time.LocalDate.of(2027, 2, 3));
        verify(snapshotRepository).saveAndFlush(any(ContentReleaseDateSnapshot.class));
    }

    @Test
    void shouldUseSeriesFirstAirDateAndNotCountEpisodes() {
        User owner = User.builder().id(UUID.randomUUID()).preferredRegion("BR").build();
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1396").type(ContentType.SERIES).build();
        WatchlistEntry entry = WatchlistEntry.builder()
                .id(UUID.randomUUID()).user(owner).content(series).type(ContentType.SERIES).position(1).build();
        TmdbTvFullDetails details = new TmdbTvFullDetails(
                "1396", "Breaking Bad", null, null, null, null, "2027-03-10", null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        when(snapshotRepository.findByTypeAndRegionIsNullAndTmdbIdIn(eq(ContentType.SERIES), eq(List.of("1396"))))
                .thenReturn(List.of());
        when(tmdbClient.getTvFullDetails("1396", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(details));
        when(snapshotRepository.saveAndFlush(any(ContentReleaseDateSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = snapshotService.resolve(owner, List.of(entry));

        assertThat(result.releaseDates()).containsEntry(entry.getId(), java.time.LocalDate.of(2027, 3, 10));
        verify(tmdbClient).getTvFullDetails("1396", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }

    @Test
    void shouldServeExpiredSnapshotAndScheduleRefreshAsynchronously() {
        User owner = User.builder().id(UUID.randomUUID()).preferredRegion("BR").build();
        Content movie = Content.builder().id(UUID.randomUUID()).tmdbId("550").type(ContentType.MOVIE).build();
        WatchlistEntry entry = WatchlistEntry.builder()
                .id(UUID.randomUUID()).user(owner).content(movie).type(ContentType.MOVIE).position(1).build();
        ContentReleaseDateSnapshot snapshot = ContentReleaseDateSnapshot.builder()
                .tmdbId("550").type(ContentType.MOVIE).region("BR")
                .releaseDate(java.time.LocalDate.of(2027, 2, 3))
                .status(ContentReleaseDateSnapshot.Status.FOUND)
                .lastCheckedAt(LocalDateTime.now().minusDays(2))
                .nextCheckAt(LocalDateTime.now().minusHours(1))
                .build();
        when(snapshotRepository.findByTypeAndRegionAndTmdbIdIn(
                eq(ContentType.MOVIE), eq("BR"), eq(List.of("550"))))
                .thenReturn(List.of(snapshot));

        var result = snapshotService.resolve(owner, List.of(entry));

        assertThat(result.releaseDates()).containsEntry(entry.getId(), java.time.LocalDate.of(2027, 2, 3));
        verify(snapshotRefreshExecutor).execute(any(Runnable.class));
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void shouldReuseFreshSnapshotDuringWriteThrough() {
        User owner = User.builder().id(UUID.randomUUID()).preferredRegion("BR").build();
        Content movie = Content.builder().id(UUID.randomUUID()).tmdbId("550").type(ContentType.MOVIE).build();
        ContentReleaseDateSnapshot snapshot = ContentReleaseDateSnapshot.builder()
                .tmdbId("550").type(ContentType.MOVIE).region("BR")
                .releaseDate(java.time.LocalDate.of(2027, 2, 3))
                .status(ContentReleaseDateSnapshot.Status.FOUND)
                .lastCheckedAt(LocalDateTime.now())
                .nextCheckAt(LocalDateTime.now().plusHours(1))
                .build();
        when(snapshotRepository.findByTypeAndRegionAndTmdbId(
                ContentType.MOVIE, "BR", "550")).thenReturn(Optional.of(snapshot));

        snapshotService.writeThrough(owner, movie);

        verifyNoInteractions(tmdbClient);
        verify(snapshotRepository).findByTypeAndRegionAndTmdbId(ContentType.MOVIE, "BR", "550");
    }

    @Test
    void shouldFailWhenMissingSnapshotCannotBeFilled() {
        User owner = User.builder().id(UUID.randomUUID()).preferredRegion("BR").build();
        Content movie = Content.builder().id(UUID.randomUUID()).tmdbId("550").type(ContentType.MOVIE).build();
        WatchlistEntry entry = WatchlistEntry.builder()
                .id(UUID.randomUUID()).user(owner).content(movie).type(ContentType.MOVIE).position(1).build();
        when(snapshotRepository.findByTypeAndRegionAndTmdbIdIn(
                eq(ContentType.MOVIE), eq("BR"), eq(List.of("550"))))
                .thenReturn(List.of());
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> snapshotService.resolve(owner, List.of(entry)))
                .isInstanceOf(TmdbUnavailableException.class);
        verify(snapshotRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void shouldPreservePreviousDateWhenExpiredRefreshIsUnavailable() {
        User owner = User.builder().id(UUID.randomUUID()).preferredRegion("BR").build();
        Content movie = Content.builder().id(UUID.randomUUID()).tmdbId("550").type(ContentType.MOVIE).build();
        WatchlistEntry entry = WatchlistEntry.builder()
                .id(UUID.randomUUID()).user(owner).content(movie).type(ContentType.MOVIE).position(1).build();
        ContentReleaseDateSnapshot snapshot = ContentReleaseDateSnapshot.builder()
                .tmdbId("550").type(ContentType.MOVIE).region("BR")
                .releaseDate(java.time.LocalDate.of(2027, 2, 3))
                .status(ContentReleaseDateSnapshot.Status.FOUND)
                .lastCheckedAt(LocalDateTime.now().minusDays(2))
                .nextCheckAt(LocalDateTime.now().minusHours(1))
                .build();
        when(snapshotRepository.findByTypeAndRegionAndTmdbIdIn(
                eq(ContentType.MOVIE), eq("BR"), eq(List.of("550"))))
                .thenReturn(List.of(snapshot));
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Unavailable<>());
        org.mockito.Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(snapshotRefreshExecutor).execute(any(Runnable.class));

        var result = snapshotService.resolve(owner, List.of(entry));

        assertThat(result.releaseDates()).containsEntry(entry.getId(), java.time.LocalDate.of(2027, 2, 3));
        assertThat(snapshot.getReleaseDate()).isEqualTo(java.time.LocalDate.of(2027, 2, 3));
        verify(snapshotRepository).saveAndFlush(snapshot);
    }
}
