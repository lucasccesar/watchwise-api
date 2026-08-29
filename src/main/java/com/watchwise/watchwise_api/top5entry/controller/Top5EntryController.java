package com.watchwise.watchwise_api.top5entry.controller;

import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryCreationDTO;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryPatchDTO;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;
import com.watchwise.watchwise_api.top5entry.service.Top5EntryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class Top5EntryController {

    private final Top5EntryService top5EntryService;

    @GetMapping("/{userId}/top5/{type}")
    public ResponseEntity<List<Top5EntryResponseDTO>> getTop5(
            @PathVariable UUID userId,
            @PathVariable MovieOrSeriesType type
    ) {
        return ResponseEntity.ok(top5EntryService.getTop5(getCurrentUserId(), userId, type.toContentType()));
    }

    @PostMapping("/me/top5/{type}")
    public ResponseEntity<Top5EntryResponseDTO> insertEntry(
            @PathVariable MovieOrSeriesType type,
            @Valid @RequestBody Top5EntryCreationDTO top5EntryCreationDTO
    ) {
        Top5EntryResponseDTO created = top5EntryService.insertEntry(getCurrentUserId(), type.toContentType(), top5EntryCreationDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/me/top5/{type}/{top5EntryId}")
    public ResponseEntity<Void> removeEntry(
            @PathVariable MovieOrSeriesType type,
            @PathVariable UUID top5EntryId
    ) {
        top5EntryService.removeEntry(getCurrentUserId(), type.toContentType(), top5EntryId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/me/top5/{type}/{top5EntryId}")
    public ResponseEntity<Top5EntryResponseDTO> updateEntry(
            @PathVariable MovieOrSeriesType type,
            @PathVariable UUID top5EntryId,
            @Valid @RequestBody Top5EntryPatchDTO top5EntryPatchDTO
    ) {
        Top5EntryResponseDTO updated = top5EntryService.updateEntry(getCurrentUserId(), type.toContentType(), top5EntryId, top5EntryPatchDTO);
        return ResponseEntity.ok(updated);
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
