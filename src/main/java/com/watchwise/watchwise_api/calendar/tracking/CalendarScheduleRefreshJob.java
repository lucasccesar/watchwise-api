package com.watchwise.watchwise_api.calendar.tracking;

import com.watchwise.watchwise_api.calendar.service.impl.CalendarScheduleRefreshService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CalendarScheduleRefreshJob {

    private final CalendarScheduleRefreshService refreshService;

    @Scheduled(cron = "${app.calendar-schedule-refresh.cron}")
    public void run() {
        refreshService.refreshDueSchedules();
    }
}
