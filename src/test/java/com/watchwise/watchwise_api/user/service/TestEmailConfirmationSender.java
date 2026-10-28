package com.watchwise.watchwise_api.user.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local-only provider for integration tests; it records tokens and performs no network I/O. */
@Component
public class TestEmailConfirmationSender implements EmailConfirmationSender {

    private final Map<String, String> tokensByEmail = new ConcurrentHashMap<>();

    @Override
    public void sendConfirmation(String email, String rawToken) throws IOException {
        tokensByEmail.put(email.toLowerCase(java.util.Locale.ROOT), rawToken);
    }

    public String tokenFor(String email) {
        String token = tokensByEmail.get(email.toLowerCase(java.util.Locale.ROOT));
        if (token == null) {
            throw new IllegalStateException("No email confirmation token was recorded for the test address");
        }
        return token;
    }

    public void clear() {
        tokensByEmail.clear();
    }
}
