package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateOptionCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateOptionDTO;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateOptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/picks-templates/{templateId}/categories/{categoryId}/options")
@RequiredArgsConstructor
public class PicksTemplateOptionController {
    private final PicksTemplateOptionService picksTemplateOptionService;

    @PostMapping
    public ResponseEntity<PicksTemplateOptionDTO> addOption(@PathVariable UUID templateId, @PathVariable UUID categoryId,
                                                             @Valid @RequestBody PicksTemplateOptionCreationDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(picksTemplateOptionService.addOption(currentUserId(), templateId, categoryId, dto));
    }

    @GetMapping
    public ResponseEntity<PageResponseDTO<PickOptionSearchDTO>> searchOptions(@PathVariable UUID templateId,
            @PathVariable UUID categoryId, @RequestParam(required = false) String query,
            @RequestParam(required = false) String seriesTmdbId, @RequestParam(required = false) Integer seasonNumber,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Page<PickOptionSearchDTO> options = picksTemplateOptionService.searchOptions(currentUserId(), templateId, categoryId,
                query, seriesTmdbId, seasonNumber, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(options));
    }

    @DeleteMapping("/{optionId}")
    public ResponseEntity<Void> deleteOption(@PathVariable UUID templateId, @PathVariable UUID categoryId,
                                              @PathVariable UUID optionId) {
        picksTemplateOptionService.deleteOption(currentUserId(), templateId, categoryId, optionId);
        return ResponseEntity.noContent().build();
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
