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
        if (ownerId == null || reference == null) {
            throw new IllegalArgumentException("Owner and media reference are required");
        }

        if (reference.type() != ContentType.MOVIE && reference.type() != ContentType.SERIES) {
            throw new BadRequestException("Watchlist supports MOVIE and SERIES content only");
        }
        String type = reference.type().name();
        String tmdbId = reference.tmdbId();

        Set<String> evidence = new LinkedHashSet<>();
        if (diaryEntryRepository.existsWatchEvidenceForWatchlist(ownerId, type, tmdbId)) {
            evidence.add(DIARY_ENTRY);
        }
        if (importedWatchAssertionRepository.existsUnknownWatchEvidenceForWatchlist(
                ownerId, type, tmdbId)) {
            evidence.add(IMPORTED_WATCH_ASSERTION);
        }

        boolean allowed = evidence.isEmpty();
        return new WatchlistEligibility(allowed, allowed ? null : WATCHED_CONTENT, evidence);
    }
}
