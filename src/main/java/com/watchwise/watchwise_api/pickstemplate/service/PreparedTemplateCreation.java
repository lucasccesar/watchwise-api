package com.watchwise.watchwise_api.pickstemplate.service;

import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PreparedTemplateCreation(
        UUID actorId,
        PicksTemplateCreationDTO sourceDto,
        List<PreparedTemplateOption> options,
        String validationDigest,
        LocalDateTime validatedAt) {

    public static final Duration VALIDATION_TTL = Duration.ofHours(24);

    public PreparedTemplateCreation {
        if (actorId == null || sourceDto == null || options == null
                || validationDigest == null || validationDigest.isBlank() || validatedAt == null) {
            throw new IllegalArgumentException("Prepared template creation is invalid");
        }
        options = List.copyOf(options);
    }

    public boolean isFreshAt(LocalDateTime now) {
        Duration age = Duration.between(validatedAt, now);
        return !age.isNegative() && age.compareTo(VALIDATION_TTL) < 0;
    }
}
