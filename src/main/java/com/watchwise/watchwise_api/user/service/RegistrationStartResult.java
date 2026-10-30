package com.watchwise.watchwise_api.user.service;

import java.util.UUID;

public record RegistrationStartResult(UUID userId, String registrationProof) {

    public RegistrationStartResult {
        if ((userId == null) == (registrationProof == null)) {
            throw new IllegalArgumentException("Exactly one registration result value must be present");
        }
    }

    public static RegistrationStartResult pending(String registrationProof) {
        return new RegistrationStartResult(null, registrationProof);
    }

    public static RegistrationStartResult autoConfirmed(UUID userId) {
        return new RegistrationStartResult(userId, null);
    }

    public boolean isAutoConfirmed() {
        return userId != null;
    }
}
