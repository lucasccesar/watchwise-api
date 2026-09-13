package com.watchwise.watchwise_api.calendar.service;

public sealed interface CalendarScheduleLookup {

    record Found(CalendarScheduleBatch batch) implements CalendarScheduleLookup {
    }

    record FoundSeries(CalendarSeriesSchedule schedule) implements CalendarScheduleLookup {
    }

    record NotFound() implements CalendarScheduleLookup {
    }

    record Unavailable() implements CalendarScheduleLookup {
    }
}
