package com.watchwise.watchwise_api.watchlist.dto;

import jakarta.validation.constraints.NotBlank;

public record WatchlistEntryCreationDTO(
        @NotBlank String tmdbId
) {
}