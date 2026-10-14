package com.watchwise.watchwise_api.dailygame.dto;

import org.springframework.http.MediaType;

public record DailyGameMediaResponseDTO(byte[] body, MediaType mediaType) {}
