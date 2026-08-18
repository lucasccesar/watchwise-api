package com.watchwise.watchwise_api.watchlist.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record WatchlistEntryReorderDTO(
        @NotNull @Min(1) Integer position
) {
}