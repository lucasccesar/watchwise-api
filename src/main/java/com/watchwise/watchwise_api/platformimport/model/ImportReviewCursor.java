package com.watchwise.watchwise_api.platformimport.model;

import com.watchwise.watchwise_api.common.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public record ImportReviewCursor(long generation, long revision, UUID itemId) {
    public ImportReviewCursor {
        if (generation < 0 || revision < 0 || itemId == null) {
            throw new IllegalArgumentException("Review cursor is invalid");
        }
    }

    public String encode() {
        String value = "1|" + generation + "|" + revision + "|" + itemId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static ImportReviewCursor first(long generation) {
        return new ImportReviewCursor(generation, 0, new UUID(0, 0));
    }

    public static ImportReviewCursor decode(String encoded) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 4 || !"1".equals(parts[0])) {
                throw new IllegalArgumentException("Unsupported cursor version");
            }
            return new ImportReviewCursor(Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                    UUID.fromString(parts[3]));
        } catch (RuntimeException exception) {
            throw new BadRequestException("Review cursor is invalid");
        }
    }
}
