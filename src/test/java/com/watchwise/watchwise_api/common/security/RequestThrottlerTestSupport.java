package com.watchwise.watchwise_api.common.security;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

public final class RequestThrottlerTestSupport {
    private RequestThrottlerTestSupport() {
    }

    public static void reset(RequestThrottler requestThrottler) {
        SharedLimiterStore store = (SharedLimiterStore) ReflectionTestUtils.getField(requestThrottler, "store");
        DataSource dataSource = (DataSource) ReflectionTestUtils.getField(store, "dataSource");
        new JdbcTemplate(dataSource).execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("DELETE FROM shared_limiter_state WHERE kind = 'request'")) {
                statement.executeUpdate();
            }
            connection.commit();
            return null;
        });
    }
}
