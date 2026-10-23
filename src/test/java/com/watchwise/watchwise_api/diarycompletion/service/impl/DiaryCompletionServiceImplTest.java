package com.watchwise.watchwise_api.diarycompletion.service.impl;

import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.common.transaction.UserContentMutationLock;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diarycompletion.entity.UserSeriesHistoryState;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    private DiaryEntryRepository diaryEntryRepository;
    @Mock
    private ContentRepository contentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserContentMutationLock userContentMutationLock;
    @Mock
    private NewTransactionExecutor newTransactionExecutor;

    @InjectMocks
    private DiaryCompletionServiceImpl service;

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
        when(completionCalculator.calculate(any(), any())).thenAnswer(invocation -> {
            calculatorInputs.add(invocation.getArgument(1));
            return List.of();
        });
        doAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(0).get())
                .when(newTransactionExecutor).runInNewTransaction(any());

        service.refreshStaleScopes(OWNER_ID, Instant.parse("2026-10-23T00:00:00Z"));

        assertThat(watchCalls).hasValue(2);
        assertThat(calculatorInputs).hasSize(2);
        assertThat(calculatorInputs.getLast())
                .extracting(EpisodeWatch::diaryEntryId)
                .containsExactly(secondWatch.getDiaryEntryId());
        ArgumentCaptor<UserSeriesHistoryState> savedState = ArgumentCaptor.forClass(UserSeriesHistoryState.class);
        org.mockito.Mockito.verify(historyStateRepository).saveAndFlush(savedState.capture());
        assertThat(savedState.getValue().getHistoryRevision()).isEqualTo(1L);
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
}
