package com.watchwise.watchwise_api.diaryentry.controller;

import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.service.DiaryEntryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class DiaryEntryController {

    private final DiaryEntryService diaryEntryService;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.diary-action.max-requests}")
    private int diaryActionMaxRequests;
    @Value("${app.rate-limit.diary-action.window-minutes}")
    private long diaryActionWindowMinutes;

    @GetMapping("/users/{userId}/diary")
    public ResponseEntity<List<DiaryEntryResponseDTO>> getDiaryEntries(
            @PathVariable UUID userId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<DiaryEntryResponseDTO> entries = diaryEntryService.getDiaryEntries(getCurrentUserId(), userId, year, page, size);
        return ResponseEntity.ok(entries.getContent());
    }

    @PostMapping("/diary")
    public ResponseEntity<DiaryEntryResponseDTO> createDiaryEntry(@Valid @RequestBody DiaryEntryCreationDTO diaryEntryCreationDTO) {
        requestThrottler.checkAllowed(diaryActionKey(), diaryActionMaxRequests, Duration.ofMinutes(diaryActionWindowMinutes));

        DiaryEntryResponseDTO created = diaryEntryService.createDiaryEntry(getCurrentUserId(), diaryEntryCreationDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/diary/{diaryEntryId}")
    public ResponseEntity<DiaryEntryResponseDTO> updateDiaryEntry(
            @PathVariable UUID diaryEntryId,
            @Valid @RequestBody DiaryEntryCreationDTO diaryEntryCreationDTO
    ) {
        requestThrottler.checkAllowed(diaryActionKey(), diaryActionMaxRequests, Duration.ofMinutes(diaryActionWindowMinutes));

        DiaryEntryResponseDTO updated = diaryEntryService.updateDiaryEntry(getCurrentUserId(), diaryEntryId, diaryEntryCreationDTO);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/diary/{diaryEntryId}")
    public ResponseEntity<Void> deleteDiaryEntry(@PathVariable UUID diaryEntryId) {
        diaryEntryService.deleteDiaryEntry(getCurrentUserId(), diaryEntryId);
        return ResponseEntity.noContent().build();
    }

    private String diaryActionKey() {
        return "diary-action|" + getCurrentUserId();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}