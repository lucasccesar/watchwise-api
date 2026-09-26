package com.watchwise.watchwise_api.contentposter.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserContentPosterResponseDTO(UUID contentId, String customPosterUrl, LocalDateTime updatedAt) {
}
