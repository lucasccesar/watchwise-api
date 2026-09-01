package com.watchwise.watchwise_api.content.dto;

import java.time.Instant;

public record VideoDTO(String key, String name, String site, String type, Boolean official, String language, Instant publishedAt) {
}
