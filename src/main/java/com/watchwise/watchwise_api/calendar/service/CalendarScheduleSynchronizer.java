package com.watchwise.watchwise_api.calendar.service;

import java.time.Instant;

public interface CalendarScheduleSynchronizer {

    boolean synchronize(CalendarScheduleBatch batch, Instant checkedAt);

    void synchronizeSeries(CalendarSeriesSchedule schedule, Instant checkedAt);
}
