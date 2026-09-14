package com.watchwise.watchwise_api.calendar.service;

public sealed interface CalendarScheduleLookup {

    record Found(CalendarScheduleBatch batch) implements CalendarScheduleLookup {
    }

    record FoundSeries(CalendarSeriesSchedule schedule) implements CalendarScheduleLookup {
    }

    record NotFound(Integer seasonNumber) implements CalendarScheduleLookup {

        public NotFound() {
            this(null);
        }
    }

    record Unavailable() implements CalendarScheduleLookup {
    }
}
