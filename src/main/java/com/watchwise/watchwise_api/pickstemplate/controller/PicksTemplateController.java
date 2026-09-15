package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePatchDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateResponseDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/picks-templates")
@RequiredArgsConstructor
public class PicksTemplateController {
    private final PicksTemplateService picksTemplateService;

    @PostMapping
    public ResponseEntity<PicksTemplateResponseDTO> createTemplate(@Valid @RequestBody PicksTemplateCreationDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(picksTemplateService.createTemplate(currentUserId(), dto));
    }

    @GetMapping
    public ResponseEntity<PageResponseDTO<PicksTemplatePreviewDTO>> listTemplates(
            @RequestParam(required = false) PickOrigin origin, @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Page<PicksTemplatePreviewDTO> templates = picksTemplateService.listTemplates(currentUserId(), origin, name, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(templates));
    }

    @GetMapping("/{templateId}")
    public ResponseEntity<PicksTemplateResponseDTO> getTemplate(@PathVariable UUID templateId) {
        return ResponseEntity.ok(picksTemplateService.getTemplate(currentUserId(), templateId));
    }

    @PatchMapping("/{templateId}")
    public ResponseEntity<PicksTemplateResponseDTO> updateTemplate(@PathVariable UUID templateId,
                                                                      @Valid @RequestBody PicksTemplatePatchDTO dto) {
        return ResponseEntity.ok(picksTemplateService.updateTemplate(currentUserId(), templateId, dto));
    }

    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> detachOrDeleteTemplate(@PathVariable UUID templateId) {
        picksTemplateService.detachOrDeleteTemplate(currentUserId(), templateId);
        return ResponseEntity.noContent().build();
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
