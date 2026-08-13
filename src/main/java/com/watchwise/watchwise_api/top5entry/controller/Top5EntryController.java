package com.watchwise.watchwise_api.top5entry.controller;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryCreationDTO;
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
            @PathVariable ContentType type
    ) {
        return ResponseEntity.ok(top5EntryService.getTop5(getCurrentUserId(), userId, type));
    }

    @PostMapping("/me/top5/{type}")
    public ResponseEntity<Top5EntryResponseDTO> insertEntry(
            @PathVariable ContentType type,
            @Valid @RequestBody Top5EntryCreationDTO top5EntryCreationDTO
    ) {
        Top5EntryResponseDTO created = top5EntryService.insertEntry(getCurrentUserId(), type, top5EntryCreationDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/me/top5/{type}/{top5EntryId}")
    public ResponseEntity<Void> removeEntry(
            @PathVariable ContentType type,
            @PathVariable UUID top5EntryId
    ) {
        top5EntryService.removeEntry(getCurrentUserId(), type, top5EntryId);
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
