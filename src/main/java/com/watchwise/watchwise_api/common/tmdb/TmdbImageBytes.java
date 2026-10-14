package com.watchwise.watchwise_api.common.tmdb;

import org.springframework.http.MediaType;

public record TmdbImageBytes(byte[] body, MediaType mediaType) {}
