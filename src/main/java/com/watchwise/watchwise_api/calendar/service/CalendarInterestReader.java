package com.watchwise.watchwise_api.calendar.service;

import java.util.UUID;

public interface CalendarInterestReader {

    CalendarInterest read(UUID userId);
}
