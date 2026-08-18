package com.watchwise.watchwise_api.watchlist.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryCreationDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryReorderDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryResponseDTO;
import com.watchwise.watchwise_api.watchlist.service.WatchlistEntryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class WatchlistEntryController {

    private final WatchlistEntryService watchlistEntryService;

    @GetMapping("/{userId}/watchlist/{type}")
    public ResponseEntity<PageResponseDTO<WatchlistEntryResponseDTO>> getWatchlist(
            @PathVariable UUID userId,
            @PathVariable ContentType type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<WatchlistEntryResponseDTO> entries = watchlistEntryService.getWatchlist(getCurrentUserId(), userId, type, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(entries));
    }

    @PostMapping("/me/watchlist/{type}")
    public ResponseEntity<WatchlistEntryResponseDTO> insertEntry(
            @PathVariable ContentType type,
            @Valid @RequestBody WatchlistEntryCreationDTO watchlistEntryCreationDTO
    ) {
        WatchlistEntryResponseDTO created = watchlistEntryService.insertEntry(getCurrentUserId(), type, watchlistEntryCreationDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/me/watchlist/{type}/{watchlistEntryId}")
    public ResponseEntity<WatchlistEntryResponseDTO> moveEntry(
            @PathVariable ContentType type,
            @PathVariable UUID watchlistEntryId,
            @Valid @RequestBody WatchlistEntryReorderDTO watchlistEntryReorderDTO
    ) {
        WatchlistEntryResponseDTO moved = watchlistEntryService.moveEntry(getCurrentUserId(), type, watchlistEntryId, watchlistEntryReorderDTO);
        return ResponseEntity.ok(moved);
    }

    @DeleteMapping("/me/watchlist/{type}/{watchlistEntryId}")
    public ResponseEntity<Void> removeEntry(
            @PathVariable ContentType type,
            @PathVariable UUID watchlistEntryId
    ) {
        watchlistEntryService.removeEntry(getCurrentUserId(), type, watchlistEntryId);
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}