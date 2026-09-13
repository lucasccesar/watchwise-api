package com.watchwise.watchwise_api.calendar.repository;

@FunctionalInterface
public interface CalendarScheduleIdentityLock {

    void lock(String identity);
}
