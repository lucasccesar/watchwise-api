package com.watchwise.watchwise_api.calendar.tracking;

import com.watchwise.watchwise_api.calendar.service.impl.CalendarScheduleRefreshService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CalendarScheduleRefreshJobTest {

    @Mock
    private CalendarScheduleRefreshService refreshService;

    @InjectMocks
    private CalendarScheduleRefreshJob refreshJob;

    @Test
    @DisplayName("[run] Should Delegate To CalendarScheduleRefreshService")
    void shouldDelegateToCalendarScheduleRefreshService() {
        refreshJob.run();

        verify(refreshService).refreshDueSchedules();
    }
}
