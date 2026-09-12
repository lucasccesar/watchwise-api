package com.watchwise.watchwise_api.calendar.service;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface CalendarWatchedContentReader {

    Set<WatchedCalendarKey> readWatchedKeys(UUID userId, Collection<WatchedCalendarKey> requestedKeys);
}
