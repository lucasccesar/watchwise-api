package com.watchwise.watchwise_api.calendar.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class PostgresCalendarScheduleIdentityLock implements CalendarScheduleIdentityLock {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void lock(String identity) {
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", String.class, identity);
    }
}
