package com.watchwise.watchwise_api.common.security;

public final class RequestThrottlerTestSupport {

    private RequestThrottlerTestSupport() {
    }

    public static void reset(RequestThrottler requestThrottler) {
        requestThrottler.reset();
    }
}
