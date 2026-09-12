package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.calendar.dto.CalendarSource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record CalendarInterest(
        List<String> movieTmdbIds,
        List<String> seriesTmdbIds,
        List<String> inProgressSeriesTmdbIds,
        Map<CalendarScheduleKey, Set<CalendarSource>> sourcesByKey,
        String preferredLanguage,
        String preferredRegion) {

    public CalendarInterest {
        movieTmdbIds = List.copyOf(movieTmdbIds);
        seriesTmdbIds = List.copyOf(seriesTmdbIds);
        inProgressSeriesTmdbIds = List.copyOf(inProgressSeriesTmdbIds);
        sourcesByKey = immutableSourcesByKey(sourcesByKey);
    }

    private static Map<CalendarScheduleKey, Set<CalendarSource>> immutableSourcesByKey(
            Map<CalendarScheduleKey, Set<CalendarSource>> sourcesByKey) {
        LinkedHashMap<CalendarScheduleKey, Set<CalendarSource>> copy = new LinkedHashMap<>();
        sourcesByKey.forEach((key, sources) -> copy.put(
                key,
                Collections.unmodifiableSet(new LinkedHashSet<>(sources))));
        return Collections.unmodifiableMap(copy);
    }
}
