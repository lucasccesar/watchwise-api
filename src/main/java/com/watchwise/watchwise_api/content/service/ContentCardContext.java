package com.watchwise.watchwise_api.content.service;

import java.util.UUID;

public record ContentCardContext(
        String language,
        String region,
        UUID posterUserId,
        UUID viewerId) {
}
