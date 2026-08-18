package com.watchwise.watchwise_api.watchlist.service;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryCreationDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryReorderDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryResponseDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface WatchlistEntryService {

    Page<WatchlistEntryResponseDTO> getWatchlist(UUID viewerId, UUID userId, ContentType type, Integer pageNumber, Integer pageSize);

    WatchlistEntryResponseDTO insertEntry(UUID userId, ContentType type, WatchlistEntryCreationDTO watchlistEntryCreationDTO);

    void removeEntry(UUID userId, ContentType type, UUID watchlistEntryId);

    void removeEntryIfPresent(UUID userId, ContentType type, UUID contentId);

    WatchlistEntryResponseDTO moveEntry(UUID userId, ContentType type, UUID watchlistEntryId, WatchlistEntryReorderDTO watchlistEntryReorderDTO);

}