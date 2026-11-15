package com.watchwise.watchwise_api.diarycompletion.service.impl;

import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotRepository;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleProvider;
import com.watchwise.watchwise_api.calendar.service.impl.CalendarScheduleRefreshService;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.common.transaction.UserContentMutationLock;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diarycompletion.entity.UserSeriesHistoryState;
import com.watchwise.watchwise_api.diarycompletion.entity.DiaryCompletionProjection;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeWatch;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.repository.DiaryCompletionProjectionRepository;
import com.watchwise.watchwise_api.diarycompletion.repository.UserSeriesHistoryStateRepository;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionCalculator;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.atLeastOnce;

@ExtendWith(MockitoExtension.class)
class DiaryCompletionServiceImplTest {

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final String SERIES_ID = "1396";

    @Mock
    private CompletionScopeLoader scopeLoader;
    @Mock
    private CompletionCalculator completionCalculator;
    @Mock
    private UserSeriesHistoryStateRepository historyStateRepository;
    @Mock
    private DiaryCompletionProjectionRepository projectionRepository;
    @Mock
    private CalendarScheduleCompletenessRepository completenessRepository;
    @Mock
    private CalendarScheduleSnapshotRepository snapshotRepository;
    @Mock
    private CalendarScheduleProvider scheduleProvider;
    @Mock
    private DiaryEntryRepository diaryEntryRepository;
    @Mock
    private ContentRepository contentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserContentMutationLock userContentMutationLock;
    @Mock
    private NewTransactionExecutor newTransactionExecutor;
    @Mock
    private CalendarScheduleRefreshService calendarScheduleRefreshService;

    @InjectMocks
    private DiaryCompletionServiceImpl service;

    @Test
    void shouldRotateSeasonRepairCandidatesBetweenBoundedDiaryReads() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "calendarScheduleRefreshService", calendarScheduleRefreshService);
        User owner = User.builder()
                .id(OWNER_ID)
                .preferredRegion("US")
                .preferredLanguage("en-US")
                .build();
        UserSeriesHistoryState state = UserSeriesHistoryState.builder()
                .id(new UserSeriesHistoryState.Key(OWNER_ID, SERIES_ID))
                .historyRevision(1L)
                .computedRevision(1L)
                .scheduleVersion(0L)
                .validUntil(Instant.parse("2099-01-01T00:00:00Z"))
                .build();
        LocalDateTime checkedAt = LocalDateTime.of(2026, 10, 23, 0, 0);
        List<DiaryEntryRepository.SeasonScheduleRepairCandidate> firstPage = repairCandidates(0);
        List<DiaryEntryRepository.SeasonScheduleRepairCandidate> secondPage = repairCandidates(32);

        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(diaryEntryRepository.findSeriesTmdbIdsForDiaryView(OWNER_ID)).thenReturn(List.of(SERIES_ID));
        when(historyStateRepository.findByUserIdAndSeriesTmdbIdIn(OWNER_ID, List.of(SERIES_ID)))
                .thenReturn(List.of(state));
        when(completenessRepository.findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                any(), anyCollection(), anyString(), anyString())).thenReturn(List.of());
        when(projectionRepository.findSeasonProjectionVersions(OWNER_ID)).thenReturn(List.of());
        when(scopeLoader.supportsPersistedSchedules()).thenReturn(true);
        when(diaryEntryRepository.findSeasonScheduleRepairCandidates(OWNER_ID, checkedAt, 32, 0))
                .thenReturn(firstPage);
        when(diaryEntryRepository.findSeasonScheduleRepairCandidates(OWNER_ID, checkedAt, 32, 32))
                .thenReturn(secondPage);
        when(diaryEntryRepository.findSeasonCompletionCandidates(
                eq(OWNER_ID), any(LocalDate.class), any(LocalDateTime.class), any(Instant.class), anyInt()))
                .thenReturn(List.of());
        when(calendarScheduleRefreshService.refreshSeasonInBackground(
                anyString(), anyInt(), anyString(), anyString())).thenReturn(false);

        Instant now = checkedAt.toInstant(ZoneOffset.UTC);
        service.refreshStaleSeasonScopes(OWNER_ID, now);
        service.refreshStaleSeasonScopes(OWNER_ID, now);

        verify(diaryEntryRepository).findSeasonScheduleRepairCandidates(OWNER_ID, checkedAt, 32, 0);
        verify(diaryEntryRepository).findSeasonScheduleRepairCandidates(OWNER_ID, checkedAt, 32, 32);
        verify(calendarScheduleRefreshService, times(64)).refreshSeasonInBackground(
                anyString(), eq(1), eq("US"), eq("en-US"));
    }

    @Test
    void shouldUseRevisionZeroForMissingStateAndReloadWatchesAfterAConcurrentFirstWrite() {
        User owner = User.builder()
                .id(OWNER_ID)
                .preferredRegion("US")
                .preferredLanguage("en-US")
                .build();
        Content series = content(ContentType.SERIES, SERIES_ID, null, null);
        Content season = content(ContentType.SEASON, null, 1, SERIES_ID);
        UserSeriesHistoryState concurrentState = UserSeriesHistoryState.builder()
                .id(new UserSeriesHistoryState.Key(OWNER_ID, SERIES_ID))
                .historyRevision(1L)
                .computedRevision(0L)
                .scheduleVersion(0L)
                .version(0L)
                .build();
        DiaryEntryRepository.EpisodeCompletionWatch firstWatch = watch(UUID.randomUUID());
        DiaryEntryRepository.EpisodeCompletionWatch secondWatch = watch(UUID.randomUUID());
        List<List<EpisodeWatch>> calculatorInputs = new ArrayList<>();
        AtomicInteger watchCalls = new AtomicInteger();

        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(diaryEntryRepository.findSeriesTmdbIdsForDiaryView(OWNER_ID)).thenReturn(List.of(SERIES_ID));
        when(historyStateRepository.findByUserIdAndSeriesTmdbIdIn(OWNER_ID, List.of(SERIES_ID)))
                .thenReturn(List.of());
        when(historyStateRepository.findById(new UserSeriesHistoryState.Key(OWNER_ID, SERIES_ID)))
                .thenReturn(Optional.empty(), Optional.of(concurrentState), Optional.of(concurrentState), Optional.of(concurrentState));
        when(completenessRepository.findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                any(), anyCollection(), anyString(), anyString())).thenReturn(List.of());
        when(completenessRepository.findSeriesIdentity(SERIES_ID, "US", "en-US"))
                .thenReturn(Optional.empty());
        when(projectionRepository.findSeasonProjectionVersions(OWNER_ID)).thenReturn(List.of());
        when(diaryEntryRepository.findEpisodeCompletionWatches(OWNER_ID, List.of(SERIES_ID)))
                .thenAnswer(invocation -> watchCalls.getAndIncrement() == 0
                        ? List.of(firstWatch) : List.of(secondWatch));
        when(contentRepository.findByTmdbIdAndType(SERIES_ID, ContentType.SERIES))
                .thenReturn(Optional.of(series));
        when(contentRepository.findByTypeAndSeriesTmdbIdIn(ContentType.SEASON, List.of(SERIES_ID)))
                .thenReturn(List.of(season));
        when(scopeLoader.load(any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            var reference = invocation.getArgument(0, com.watchwise.watchwise_api.content.dto.MediaReference.class);
            return new VersionedCompletionScope(
                    new CompletionScope(reference, java.util.Set.of(new EpisodeCoordinate(1, 1)), true),
                    "scope-version",
                    Instant.parse("2099-01-01T00:00:00Z"));
        });
        when(scopeLoader.loadPersistedSeasons(eq(SERIES_ID), anyCollection(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    var reference = com.watchwise.watchwise_api.content.dto.MediaReference.season(SERIES_ID, 1);
                    return CompletionScopeLoader.PersistedSeasonScopes.supported(java.util.Map.of(1, new VersionedCompletionScope(
                            new CompletionScope(reference, java.util.Set.of(new EpisodeCoordinate(1, 1)), true),
                            "season-marker-v1", Instant.parse("2099-01-01T00:00:00Z"))));
                });
        when(completionCalculator.calculate(any(), any())).thenAnswer(invocation -> {
            calculatorInputs.add(invocation.getArgument(1));
            return List.of();
        });
        doAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(0).get())
                .when(newTransactionExecutor).runInNewTransaction(any());

        service.refreshStaleScopes(OWNER_ID, Instant.parse("2026-10-23T00:00:00Z"));

        assertThat(watchCalls).hasValue(2);
        assertThat(calculatorInputs).hasSize(3);
        assertThat(calculatorInputs.getLast())
                .extracting(EpisodeWatch::diaryEntryId)
                .containsExactly(secondWatch.getDiaryEntryId());
        ArgumentCaptor<UserSeriesHistoryState> savedState = ArgumentCaptor.forClass(UserSeriesHistoryState.class);
        org.mockito.Mockito.verify(historyStateRepository, org.mockito.Mockito.atLeastOnce())
                .saveAndFlush(savedState.capture());
        assertThat(savedState.getAllValues().getLast().getHistoryRevision()).isEqualTo(1L);
    }

    @Test
    void shouldPublishSeasonLocallyWhenSeriesMarkerIsIncompleteWithoutCallingTmdb() {
        Instant now = Instant.parse("2026-10-23T12:00:00Z");
        User owner = User.builder().id(OWNER_ID).preferredRegion("US").preferredLanguage("en-US").build();
        Content series = content(ContentType.SERIES, SERIES_ID, null, null);
        Content season = content(ContentType.SEASON, null, 1, SERIES_ID);
        CalendarScheduleCompleteness pendingSeries = marker(
                CalendarScheduleCompleteness.GroupType.SERIES, null, 2, false, 3L);
        CalendarScheduleCompleteness completeSeason = marker(
                CalendarScheduleCompleteness.GroupType.SEASON, 1, 1, true, 8L);
        CalendarScheduleSnapshot episode = CalendarScheduleSnapshot.builder()
                .id(UUID.randomUUID()).eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                .seriesTmdbId(SERIES_ID).seasonNumber(1).episodeNumber(1)
                .region("US").language("en-US").releaseDate(LocalDate.of(2026, 1, 1))
                .title("Episode 1").lastCheckedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .nextCheckAt(LocalDateTime.of(2099, 1, 1, 0, 0)).presentInLastTmdbSnapshot(true).build();
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(diaryEntryRepository.findSeriesTmdbIdsForDiaryView(OWNER_ID)).thenReturn(List.of(SERIES_ID));
        when(historyStateRepository.findByUserIdAndSeriesTmdbIdIn(OWNER_ID, List.of(SERIES_ID)))
                .thenReturn(List.of());
        when(historyStateRepository.findById(any())).thenReturn(Optional.empty());
        when(completenessRepository.findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                any(), anyCollection(), anyString(), anyString())).thenAnswer(invocation ->
                invocation.getArgument(0) == CalendarScheduleCompleteness.GroupType.SERIES
                        ? List.of(pendingSeries) : List.of(completeSeason));
        when(completenessRepository.findSeriesIdentity(SERIES_ID, "US", "en-US"))
                .thenReturn(Optional.of(pendingSeries));
        when(completenessRepository.findByGroupTypeAndSeriesTmdbIdAndSeasonNumberInAndRegionAndLanguage(
                CalendarScheduleCompleteness.GroupType.SEASON, SERIES_ID, List.of(1), "US", "en-US"))
                .thenReturn(List.of(completeSeason));
        when(snapshotRepository.findByEventTypeAndSeriesTmdbIdAndSeasonNumberInAndRegionAndLanguage(
                CalendarScheduleSnapshot.EventType.EPISODE, SERIES_ID, List.of(1), "US", "en-US"))
                .thenReturn(List.of(episode));
        when(snapshotRepository.findByEventTypeAndSeriesTmdbIdAndRegionAndLanguage(
                CalendarScheduleSnapshot.EventType.EPISODE, SERIES_ID, "US", "en-US"))
                .thenReturn(List.of(episode));
        when(projectionRepository.findSeasonProjectionVersions(OWNER_ID)).thenReturn(List.of());
        DiaryEntryRepository.EpisodeCompletionWatch episodeWatch = watch(UUID.randomUUID());
        when(diaryEntryRepository.findEpisodeCompletionWatches(OWNER_ID, List.of(SERIES_ID)))
                .thenReturn(List.of(episodeWatch));
        when(contentRepository.findByTmdbIdAndType(SERIES_ID, ContentType.SERIES))
                .thenReturn(Optional.of(series));
        when(contentRepository.findByTypeAndSeriesTmdbIdIn(ContentType.SEASON, List.of(SERIES_ID)))
                .thenReturn(List.of(season));
        doAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(0).get())
                .when(newTransactionExecutor).runInNewTransaction(any());

        DiaryCompletionServiceImpl localService = new DiaryCompletionServiceImpl(
                new CalendarCompletionScopeLoader(scheduleProvider, completenessRepository, snapshotRepository),
                new CompletionCalculator(), historyStateRepository, projectionRepository, completenessRepository,
                diaryEntryRepository, contentRepository, userRepository, userContentMutationLock,
                newTransactionExecutor);
        localService.refreshStaleSeasonScopes(OWNER_ID, now);

        org.mockito.ArgumentCaptor<Iterable<DiaryCompletionProjection>> saved =
                org.mockito.ArgumentCaptor.forClass(Iterable.class);
        org.mockito.Mockito.verify(projectionRepository, atLeastOnce()).saveAll(saved.capture());
        assertThat(saved.getAllValues().stream()
                .flatMap(values -> java.util.stream.StreamSupport.stream(values.spliterator(), false))
                .map(DiaryCompletionProjection::getContentType).toList())
                .containsOnly(ContentType.SEASON);
        verifyNoInteractions(scheduleProvider);
    }

    private Content content(ContentType type, String tmdbId, Integer seasonNumber, String seriesTmdbId) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .id(UUID.randomUUID())
                .type(type)
                .tmdbId(tmdbId)
                .seasonNumber(seasonNumber)
                .seriesTmdbId(seriesTmdbId)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private List<DiaryEntryRepository.SeasonScheduleRepairCandidate> repairCandidates(int firstIndex) {
        List<DiaryEntryRepository.SeasonScheduleRepairCandidate> candidates = new ArrayList<>();
        for (int index = firstIndex; index < firstIndex + 32; index++) {
            DiaryEntryRepository.SeasonScheduleRepairCandidate candidate = mock(
                    DiaryEntryRepository.SeasonScheduleRepairCandidate.class);
            when(candidate.getSeriesTmdbId()).thenReturn("repair-" + index);
            when(candidate.getSeasonNumber()).thenReturn(1);
            candidates.add(candidate);
        }
        return List.copyOf(candidates);
    }

    private DiaryEntryRepository.EpisodeCompletionWatch watch(UUID diaryEntryId) {
        DiaryEntryRepository.EpisodeCompletionWatch watch = mock(DiaryEntryRepository.EpisodeCompletionWatch.class);
        when(watch.getDiaryEntryId()).thenReturn(diaryEntryId);
        when(watch.getSeasonNumber()).thenReturn(1);
        when(watch.getEpisodeNumber()).thenReturn(1);
        when(watch.getWatchNumber()).thenReturn(1);
        when(watch.getWatchedDate()).thenReturn(LocalDate.of(2026, 1, 1));
        when(watch.getWatchedAt()).thenReturn(null);
        when(watch.getRecordedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        return watch;
    }

    private CalendarScheduleCompleteness marker(
            CalendarScheduleCompleteness.GroupType type,
            Integer seasonNumber,
            int expectedCount,
            boolean complete,
            long version) {
        return CalendarScheduleCompleteness.builder()
                .id(UUID.randomUUID()).groupType(type).seriesTmdbId(SERIES_ID).seasonNumber(seasonNumber)
                .region("US").language("en-US").expectedEpisodeCount(expectedCount).complete(complete)
                .scopeVersion(version).lastCheckedAt(LocalDateTime.of(2026, 10, 23, 12, 0)).build();
    }
}
