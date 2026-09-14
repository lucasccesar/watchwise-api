package com.watchwise.watchwise_api.calendar.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class CalendarScheduleCadence {

    public static final LocalDateTime PERSISTED_NO_RECHECK_AT =
            LocalDateTime.of(294276, 12, 31, 23, 59, 59, 999_999_000);

    private static final int NEAR_FUTURE_DAYS = 7;
    private static final int SERIES_DISCOVERY_DAYS = 30;
    private static final long DAY_SECONDS = 24 * 60 * 60;

    private CalendarScheduleCadence() {
    }

    public static Instant nextCheckAt(LocalDate releaseDate, Instant checkedAt) {
        if (releaseDate == null) {
            return checkedAt.plusSeconds(7 * DAY_SECONDS);
        }
        LocalDate today = checkedAt.atZone(ZoneId.systemDefault()).toLocalDate();
        if (!releaseDate.isAfter(today)) {
            return Instant.MAX;
        }
        return releaseDate.isAfter(today.plusDays(NEAR_FUTURE_DAYS))
                ? checkedAt.plusSeconds(7 * DAY_SECONDS)
                : checkedAt.plusSeconds(DAY_SECONDS);
    }

    /** A series remains discoverable after release: new seasons have no prior episode row to become due. */
    public static Instant nextSeriesCheckAt(LocalDate releaseDate, Instant checkedAt) {
        if (releaseDate != null && !releaseDate.isAfter(checkedAt.atZone(ZoneId.systemDefault()).toLocalDate())) {
            return checkedAt.plusSeconds(SERIES_DISCOVERY_DAYS * DAY_SECONDS);
        }
        return nextCheckAt(releaseDate, checkedAt);
    }

    public static boolean isSeriesDiscoveryDue(LocalDateTime lastDiscoveredAt, Instant now) {
        return lastDiscoveredAt == null
                || !lastDiscoveredAt.plusDays(SERIES_DISCOVERY_DAYS).isAfter(
                        LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    public static Instant nextNegativeCheckAt(Instant checkedAt) {
        return checkedAt.plusSeconds(SERIES_DISCOVERY_DAYS * DAY_SECONDS);
    }
}
