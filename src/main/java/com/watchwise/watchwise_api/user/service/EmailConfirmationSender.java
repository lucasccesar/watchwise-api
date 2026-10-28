package com.watchwise.watchwise_api.user.service;

import java.io.IOException;

/** Sends account-security messages; implementations must never log or persist raw tokens. */
public interface EmailConfirmationSender {

    default boolean isConfigured() {
        return true;
    }

    void sendConfirmation(String email, String rawToken) throws IOException;

    default void sendPasswordReset(String email, String rawToken) throws IOException {
        throw new IOException("Password reset email delivery is not implemented");
    }
}
