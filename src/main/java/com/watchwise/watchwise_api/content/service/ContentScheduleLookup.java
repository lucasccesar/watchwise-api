package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public sealed interface ContentScheduleLookup
        permits ContentScheduleLookup.Found, ContentScheduleLookup.NotFound, ContentScheduleLookup.Unavailable {

    record Found(
            ContentSchedule schedule,
            TmdbLookupOrigin origin,
            Map<Integer, TmdbLookupOrigin> seasonOriginsByNumber,
            TmdbLookupOrigin seriesSummaryOrigin) implements ContentScheduleLookup {

        public Found {
            Objects.requireNonNull(schedule, "schedule is required");
            Objects.requireNonNull(origin, "origin is required");
            Objects.requireNonNull(seriesSummaryOrigin, "seriesSummaryOrigin is required");
            seasonOriginsByNumber = seasonOriginsByNumber == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(seasonOriginsByNumber));
        }

        public Found(ContentSchedule schedule) {
            this(schedule, TmdbLookupOrigin.REMOTE, Map.of(), TmdbLookupOrigin.REMOTE);
        }

        public Found(ContentSchedule schedule, TmdbLookupOrigin origin) {
            this(schedule, origin, Map.of(), origin);
        }

        public Found(
                ContentSchedule schedule,
                TmdbLookupOrigin origin,
                Map<Integer, TmdbLookupOrigin> seasonOriginsByNumber) {
            this(schedule, origin, seasonOriginsByNumber, origin);
        }
    }

    record NotFound(Integer seasonNumber) implements ContentScheduleLookup {

        public NotFound() {
            this(null);
        }
    }

    record Unavailable() implements ContentScheduleLookup {
    }
}
