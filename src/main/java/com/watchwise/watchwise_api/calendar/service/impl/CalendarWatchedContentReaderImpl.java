package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.service.CalendarWatchedContentReader;
import com.watchwise.watchwise_api.calendar.service.WatchedCalendarKey;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CalendarWatchedContentReaderImpl implements CalendarWatchedContentReader {

    private final DiaryEntryRepository diaryEntryRepository;

    @Override
    public Set<WatchedCalendarKey> readWatchedKeys(UUID userId, Collection<WatchedCalendarKey> requestedKeys) {
        if (requestedKeys.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(diaryEntryRepository.findWatchedCalendarKeys(userId, requestedKeys));
    }
}
