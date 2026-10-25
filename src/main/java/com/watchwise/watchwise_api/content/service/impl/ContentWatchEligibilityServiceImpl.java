package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.dto.WatchlistEligibility;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentWatchEligibilityService;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.ImportedWatchAssertionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentWatchEligibilityServiceImpl implements ContentWatchEligibilityService {

    private static final String DIARY_ENTRY = "DIARY_ENTRY";
    private static final String IMPORTED_WATCH_ASSERTION = "IMPORTED_WATCH_ASSERTION";
    private static final String WATCHED_CONTENT = "WATCHED_CONTENT";

    private final DiaryEntryRepository diaryEntryRepository;
    private final ImportedWatchAssertionRepository importedWatchAssertionRepository;

    @Override
    public WatchlistEligibility getWatchlistEligibility(UUID ownerId, MediaReference reference) {
        return getWatchlistEligibility(ownerId, java.util.List.of(reference)).get(reference);
    }

    @Override
    public Map<MediaReference, WatchlistEligibility> getWatchlistEligibility(
            UUID ownerId, Collection<MediaReference> references) {
        if (ownerId == null || references == null) {
            throw new IllegalArgumentException("Owner and media references are required");
        }
        Set<String> movieIds = new LinkedHashSet<>();
        Set<String> seriesIds = new LinkedHashSet<>();
        for (MediaReference reference : references) {
            if (reference == null) throw new IllegalArgumentException("Media reference is required");
            if (reference.type() == ContentType.MOVIE) movieIds.add(reference.tmdbId());
            else if (reference.type() == ContentType.SERIES) seriesIds.add(reference.tmdbId());
            else throw new BadRequestException("Watchlist supports MOVIE and SERIES content only");
        }

        Set<String> diaryMovies = movieIds.isEmpty() ? Set.of()
                : Set.copyOf(diaryEntryRepository.findWatchedContentIdsForWatchlist(ownerId,
                ContentType.MOVIE, movieIds));
        Set<String> diarySeries = seriesIds.isEmpty() ? Set.of()
                : Set.copyOf(diaryEntryRepository.findWatchedContentIdsForWatchlist(ownerId,
                ContentType.SERIES, seriesIds));
        Set<String> diaryEpisodeSeries = seriesIds.isEmpty() ? Set.of()
                : Set.copyOf(diaryEntryRepository.findWatchedSeriesIdsForWatchlist(ownerId, seriesIds));
        Set<String> assertionMovies = movieIds.isEmpty() ? Set.of()
                : Set.copyOf(importedWatchAssertionRepository.findUnknownWatchedContentIdsForWatchlist(
                ownerId, ContentType.MOVIE, movieIds));
        Set<String> assertionSeries = seriesIds.isEmpty() ? Set.of()
                : Set.copyOf(importedWatchAssertionRepository.findUnknownWatchedContentIdsForWatchlist(
                ownerId, ContentType.SERIES, seriesIds));
        Set<String> assertionChildSeries = seriesIds.isEmpty() ? Set.of()
                : Set.copyOf(importedWatchAssertionRepository.findUnknownWatchedSeriesIdsForWatchlist(
                ownerId, seriesIds));

        Map<MediaReference, WatchlistEligibility> result = new LinkedHashMap<>();
        for (MediaReference reference : references) {
            Set<String> evidence = new LinkedHashSet<>();
            if (reference.type() == ContentType.MOVIE
                    ? diaryMovies.contains(reference.tmdbId())
                    : diarySeries.contains(reference.tmdbId()) || diaryEpisodeSeries.contains(reference.tmdbId())) {
                evidence.add(DIARY_ENTRY);
            }
            if (reference.type() == ContentType.MOVIE
                    ? assertionMovies.contains(reference.tmdbId())
                    : assertionSeries.contains(reference.tmdbId()) || assertionChildSeries.contains(reference.tmdbId())) {
                evidence.add(IMPORTED_WATCH_ASSERTION);
            }
            boolean allowed = evidence.isEmpty();
            result.put(reference, new WatchlistEligibility(allowed, allowed ? null : WATCHED_CONTENT, evidence));
        }
        return Map.copyOf(result);
    }
}
