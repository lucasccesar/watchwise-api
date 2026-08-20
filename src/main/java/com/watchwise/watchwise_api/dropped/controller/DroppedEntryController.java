package com.watchwise.watchwise_api.dropped.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.dropped.dto.DroppedEntryCreationDTO;
import com.watchwise.watchwise_api.dropped.dto.DroppedEntryResponseDTO;
import com.watchwise.watchwise_api.dropped.service.DroppedEntryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class DroppedEntryController {

    private final DroppedEntryService droppedEntryService;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.dropped-action.max-requests}")
    private int droppedActionMaxRequests;
    @Value("${app.rate-limit.dropped-action.window-minutes}")
    private long droppedActionWindowMinutes;

    @GetMapping("/{userId}/dropped/{type}")
    public ResponseEntity<PageResponseDTO<DroppedEntryResponseDTO>> getDropped(
            @PathVariable UUID userId,
            @PathVariable MovieOrSeriesType type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<DroppedEntryResponseDTO> entries = droppedEntryService.getDropped(getCurrentUserId(), userId, type.toContentType(), page, size);
        return ResponseEntity.ok(PageResponseDTO.of(entries));
    }

    @PostMapping("/me/dropped/{type}/{tmdbId}")
    public ResponseEntity<Void> markAsDropped(
            @PathVariable MovieOrSeriesType type,
            @PathVariable String tmdbId,
            @Valid @RequestBody(required = false) DroppedEntryCreationDTO droppedEntryCreationDTO
    ) {
        requestThrottler.checkAllowed(droppedActionKey(), droppedActionMaxRequests, Duration.ofMinutes(droppedActionWindowMinutes));

        droppedEntryService.markAsDropped(getCurrentUserId(), type.toContentType(), tmdbId, droppedEntryCreationDTO);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me/dropped/{type}/{tmdbId}")
    public ResponseEntity<Void> unmarkAsDropped(
            @PathVariable MovieOrSeriesType type,
            @PathVariable String tmdbId
    ) {
        droppedEntryService.unmarkAsDropped(getCurrentUserId(), type.toContentType(), tmdbId);
        return ResponseEntity.noContent().build();
    }

    private String droppedActionKey() {
        return "dropped-action|" + getCurrentUserId();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
