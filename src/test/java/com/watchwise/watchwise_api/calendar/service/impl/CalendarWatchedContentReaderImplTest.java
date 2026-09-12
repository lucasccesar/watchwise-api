package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.service.WatchedCalendarKey;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarWatchedContentReaderImplTest {

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @InjectMocks
    private CalendarWatchedContentReaderImpl reader;

    private final UUID userId = UUID.randomUUID();

    @Test
    void shouldDelegateTheCompleteRequestedSetInOneRepositoryCall() {
        WatchedCalendarKey movieKey = WatchedCalendarKey.movie("550");
        WatchedCalendarKey episodeKey = WatchedCalendarKey.episode("1399", 1, 3);
        Collection<WatchedCalendarKey> requestedKeys = new LinkedHashSet<>(Set.of(movieKey, episodeKey));
        when(diaryEntryRepository.findWatchedCalendarKeys(userId, requestedKeys)).thenReturn(Set.of(episodeKey));

        Set<WatchedCalendarKey> result = reader.readWatchedKeys(userId, requestedKeys);

        assertThat(result).containsExactly(episodeKey);
        verify(diaryEntryRepository).findWatchedCalendarKeys(userId, requestedKeys);
        verifyNoMoreInteractions(diaryEntryRepository);
    }

    @Test
    void shouldReturnAnImmutableResult() {
        WatchedCalendarKey movieKey = WatchedCalendarKey.movie("550");
        Collection<WatchedCalendarKey> requestedKeys = Set.of(movieKey);
        when(diaryEntryRepository.findWatchedCalendarKeys(userId, requestedKeys)).thenReturn(Set.of(movieKey));

        Set<WatchedCalendarKey> result = reader.readWatchedKeys(userId, requestedKeys);

        assertThatThrownBy(() -> result.add(WatchedCalendarKey.movie("680")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldShortCircuitWithoutCallingTheRepositoryWhenRequestedSetIsEmpty() {
        Set<WatchedCalendarKey> result = reader.readWatchedKeys(userId, Set.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    void shouldReturnAnEmptySetWhenTheRepositoryReturnsNoWatchedKeys() {
        WatchedCalendarKey movieKey = WatchedCalendarKey.movie("550");
        Collection<WatchedCalendarKey> requestedKeys = Set.of(movieKey);
        when(diaryEntryRepository.findWatchedCalendarKeys(userId, requestedKeys)).thenReturn(Set.of());

        Set<WatchedCalendarKey> result = reader.readWatchedKeys(userId, requestedKeys);

        assertThat(result).isEmpty();
        verify(diaryEntryRepository).findWatchedCalendarKeys(userId, requestedKeys);
    }

    @Test
    void shouldReturnOnlyTheKeysProvidedByTheRepository() {
        WatchedCalendarKey movieKey = WatchedCalendarKey.movie("550");
        WatchedCalendarKey episodeKey = WatchedCalendarKey.episode("1399", 1, 3);
        Collection<WatchedCalendarKey> requestedKeys = Set.of(movieKey, episodeKey);
        when(diaryEntryRepository.findWatchedCalendarKeys(userId, requestedKeys)).thenReturn(Set.of(movieKey));

        Set<WatchedCalendarKey> result = reader.readWatchedKeys(userId, requestedKeys);

        assertThat(result).containsExactly(movieKey);
    }
}
