package com.watchwise.watchwise_api.user.service;

import java.io.IOException;

/** Implemented by the configured mail provider; never log or persist the raw confirmation token. */
public interface EmailConfirmationSender {
    void sendConfirmation(String email, String rawToken) throws IOException;
}
