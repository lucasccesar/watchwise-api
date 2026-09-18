package com.watchwise.watchwise_api.pick.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.pick.dto.PickCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickPatchDTO;
import com.watchwise.watchwise_api.pick.dto.PickPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import com.watchwise.watchwise_api.pick.dto.PickSort;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pick.service.PickSelectionService;
import com.watchwise.watchwise_api.pick.service.PickService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class PickController {
    private final PickService pickService;
    private final PickSelectionService pickSelectionService;

    @PostMapping("/picks-templates/{templateId}/picks")
    public ResponseEntity<PickResponseDTO> createPick(@PathVariable UUID templateId,
                                                      @Valid @RequestBody PickCreationDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pickService.createPick(currentUserId(), templateId, dto));
    }

    @GetMapping("/picks-templates/{templateId}/picks")
    public ResponseEntity<PageResponseDTO<PickPreviewDTO>> getTemplatePicks(@PathVariable UUID templateId,
                                                                              @RequestParam(required = false) PickSort sort,
                                                                              @RequestParam(required = false) Integer page,
                                                                              @RequestParam(required = false) Integer size) {
        Page<PickPreviewDTO> picks = pickService.getTemplatePicks(currentUserId(), templateId, sort, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(picks));
    }

    @GetMapping("/picks-templates/{templateId}/my-picks")
    public ResponseEntity<PageResponseDTO<PickPreviewDTO>> getMyPicks(@PathVariable UUID templateId,
                                                                       @RequestParam(required = false) Integer page,
                                                                       @RequestParam(required = false) Integer size) {
        Page<PickPreviewDTO> picks = pickService.getMyPicks(currentUserId(), templateId, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(picks));
    }

    @GetMapping("/picks/{pickId}")
    public ResponseEntity<PickResponseDTO> getPick(@PathVariable UUID pickId) {
        return ResponseEntity.ok(pickService.getPick(currentUserId(), pickId));
    }

    @PatchMapping("/picks/{pickId}")
    public ResponseEntity<PickResponseDTO> updatePick(@PathVariable UUID pickId,
                                                      @Valid @RequestBody PickPatchDTO dto) {
        return ResponseEntity.ok(pickService.updatePick(currentUserId(), pickId, dto));
    }

    @DeleteMapping("/picks/{pickId}")
    public ResponseEntity<Void> deletePick(@PathVariable UUID pickId) {
        pickService.deletePick(currentUserId(), pickId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{userId}/picks")
    public ResponseEntity<PageResponseDTO<PickPreviewDTO>> getUserPicks(@PathVariable UUID userId,
                                                                         @RequestParam(required = false) UUID templateId,
                                                                         @RequestParam(required = false) Integer page,
                                                                         @RequestParam(required = false) Integer size) {
        Page<PickPreviewDTO> picks = pickService.getUserPicks(currentUserId(), userId, templateId, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(picks));
    }

    @PutMapping("/picks/{pickId}/categories/{categoryId}/selection")
    public ResponseEntity<PickResponseDTO> upsertSelection(@PathVariable UUID pickId, @PathVariable UUID categoryId,
                                                          @Valid @RequestBody PickTargetDTO target) {
        return ResponseEntity.ok(pickSelectionService.upsertSelection(currentUserId(), pickId, categoryId, target));
    }

    @DeleteMapping("/picks/{pickId}/categories/{categoryId}/selection")
    public ResponseEntity<Void> deleteSelection(@PathVariable UUID pickId, @PathVariable UUID categoryId) {
        pickSelectionService.deleteSelection(currentUserId(), pickId, categoryId);
        return ResponseEntity.noContent().build();
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
