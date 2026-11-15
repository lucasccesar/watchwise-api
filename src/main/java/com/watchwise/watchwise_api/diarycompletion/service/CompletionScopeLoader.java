package com.watchwise.watchwise_api.diarycompletion.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleBatch;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;

import java.time.Instant;
import java.util.Optional;
import java.util.Collection;
import java.util.Map;

public interface CompletionScopeLoader {

    VersionedCompletionScope load(MediaReference reference, String region, String language, Instant now);

    default boolean supportsPersistedSchedules() {
        return false;
    }

    default Optional<VersionedCompletionScope> loadPersisted(
            MediaReference reference, String region, String language, Instant now) {
        return Optional.empty();
    }

    default PersistedSeasonScopes loadPersistedSeasons(
            String seriesTmdbId, Collection<Integer> seasonNumbers, String region, String language, Instant now) {
        return PersistedSeasonScopes.unsupported();
    }

    default Optional<VersionedCompletionScope> loadPersistedSeries(
            String seriesTmdbId, String region, String language, Instant now) {
        return Optional.empty();
    }

    record PersistedSeasonScopes(boolean supported, Map<Integer, VersionedCompletionScope> scopes) {
        public PersistedSeasonScopes {
            scopes = scopes == null ? Map.of() : Map.copyOf(scopes);
        }

        public static PersistedSeasonScopes unsupported() {
            return new PersistedSeasonScopes(false, Map.of());
        }

        public static PersistedSeasonScopes supported(Map<Integer, VersionedCompletionScope> scopes) {
            return new PersistedSeasonScopes(true, scopes);
        }
    }


    default VersionedCompletionScope loadPrepared(
            MediaReference reference,
            String region,
            String language,
            Instant now,
            CalendarScheduleBatch preparedSeason) {
        return load(reference, region, language, now);
    }

    default VersionedCompletionScope loadPreparedSeries(
            MediaReference reference,
            String region,
            String language,
            Instant now,
            CalendarSeriesSchedule preparedSeries) {
        return load(reference, region, language, now);
    }
}
