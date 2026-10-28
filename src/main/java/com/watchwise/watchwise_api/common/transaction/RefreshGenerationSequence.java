package com.watchwise.watchwise_api.common.transaction;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RefreshGenerationSequence {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long next() {
        return jdbcTemplate.queryForObject(
                "SELECT nextval('refresh_observation_generation_seq')", Long.class);
    }
}
