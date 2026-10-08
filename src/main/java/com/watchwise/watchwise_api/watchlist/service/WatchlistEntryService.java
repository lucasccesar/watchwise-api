package com.watchwise.watchwise_api.watchlist.service;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryCreationDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryReorderDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryResponseDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistPageResponseDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistSort;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistStatus;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistViewResponseDTO;

import java.util.UUID;

public interface WatchlistEntryService {

    WatchlistPageResponseDTO getWatchlist(UUID viewerId, UUID userId, ContentType type, Integer pageNumber, Integer pageSize);

    WatchlistViewResponseDTO getWatchlistView(
            UUID viewerId,
            UUID userId,
            ContentType type,
            String genre,
            WatchlistStatus status,
            WatchlistSort sort,
            String direction,
            Integer pageNumber,
            Integer pageSize);

    WatchlistEntryResponseDTO insertEntry(UUID userId, ContentType type, WatchlistEntryCreationDTO watchlistEntryCreationDTO);

    void removeEntry(UUID userId, ContentType type, UUID watchlistEntryId);

    void removeEntryIfPresent(UUID userId, ContentType type, UUID contentId);

    WatchlistEntryResponseDTO moveEntry(UUID userId, ContentType type, UUID watchlistEntryId, WatchlistEntryReorderDTO watchlistEntryReorderDTO);

}
