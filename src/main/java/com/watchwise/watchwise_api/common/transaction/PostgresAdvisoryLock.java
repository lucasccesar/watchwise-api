package com.watchwise.watchwise_api.common.transaction;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresAdvisoryLock implements AdvisoryLock {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void lock(String identity) {
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", String.class, identity);
    }
}
