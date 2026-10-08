package com.watchwise.watchwise_api.watchlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record WatchlistCardDTO(
        UUID id,
        ContentCardDTO card,
        Integer position,
        LocalDateTime createdAt,
        LocalDate releaseDate,
        WatchlistStatus status) {
}
