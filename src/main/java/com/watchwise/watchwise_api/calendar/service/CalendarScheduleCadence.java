package com.watchwise.watchwise_api.calendar.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public final class CalendarScheduleCadence {

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
}
