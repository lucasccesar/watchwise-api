package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.dto.CalendarSource;
import com.watchwise.watchwise_api.calendar.service.CalendarInterest;
import com.watchwise.watchwise_api.calendar.service.CalendarInterestReader;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CalendarInterestReaderImpl implements CalendarInterestReader {

    private final WatchlistEntryRepository watchlistEntryRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final UserRepository userRepository;

    @Override
    public CalendarInterest read(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        List<String> movieTmdbIds = readWatchlistTmdbIds(userId, ContentType.MOVIE);
        List<String> seriesTmdbIds = readWatchlistTmdbIds(userId, ContentType.SERIES);
        List<String> inProgressSeriesTmdbIds = distinctInEncounterOrder(
                diaryEntryRepository.findDistinctInProgressSeriesTmdbIdsByUserId(userId));

        Map<CalendarScheduleKey, Set<CalendarSource>> sourcesByKey = new LinkedHashMap<>();
        addSources(sourcesByKey, movieTmdbIds, ContentType.MOVIE, user, CalendarSource.WATCHLIST);
        addSources(sourcesByKey, seriesTmdbIds, ContentType.SERIES, user, CalendarSource.WATCHLIST);
        addSources(sourcesByKey, inProgressSeriesTmdbIds, ContentType.SERIES, user, CalendarSource.IN_PROGRESS);

        return new CalendarInterest(
                movieTmdbIds,
                seriesTmdbIds,
                inProgressSeriesTmdbIds,
                sourcesByKey,
                user.getPreferredLanguage(),
                user.getPreferredRegion());
    }

    private List<String> readWatchlistTmdbIds(UUID userId, ContentType type) {
        List<WatchlistEntry> entries = watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(userId, type);
        List<String> tmdbIds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (WatchlistEntry entry : entries) {
            String tmdbId = entry.getContent().getTmdbId();
            if (seen.add(tmdbId)) {
                tmdbIds.add(tmdbId);
            }
        }
        return List.copyOf(tmdbIds);
    }

    private List<String> distinctInEncounterOrder(List<String> tmdbIds) {
        return List.copyOf(new LinkedHashSet<>(tmdbIds));
    }

    private void addSources(
            Map<CalendarScheduleKey, Set<CalendarSource>> sourcesByKey,
            List<String> tmdbIds,
            ContentType type,
            User user,
            CalendarSource source) {
        for (String tmdbId : tmdbIds) {
            CalendarScheduleKey key = new CalendarScheduleKey(
                    type, tmdbId, user.getPreferredLanguage(), user.getPreferredRegion());
            sourcesByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(source);
        }
    }
}
