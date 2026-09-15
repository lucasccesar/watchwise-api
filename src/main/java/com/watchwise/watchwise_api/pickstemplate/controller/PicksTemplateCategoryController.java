package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryPatchDTO;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/picks-templates/{templateId}/categories")
@RequiredArgsConstructor
public class PicksTemplateCategoryController {
    private final PicksTemplateCategoryService picksTemplateCategoryService;

    @PostMapping
    public ResponseEntity<PicksTemplateCategoryDTO> addCategory(@PathVariable UUID templateId,
                                                                  @Valid @RequestBody PicksTemplateCategoryCreationDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(picksTemplateCategoryService.addCategory(currentUserId(), templateId, dto));
    }

    @PatchMapping("/{categoryId}")
    public ResponseEntity<PicksTemplateCategoryDTO> updateCategory(@PathVariable UUID templateId, @PathVariable UUID categoryId,
                                                                     @Valid @RequestBody PicksTemplateCategoryPatchDTO dto) {
        return ResponseEntity.ok(picksTemplateCategoryService.updateCategory(currentUserId(), templateId, categoryId, dto));
    }

    @DeleteMapping("/{categoryId}")
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID templateId, @PathVariable UUID categoryId) {
        picksTemplateCategoryService.deleteCategory(currentUserId(), templateId, categoryId);
        return ResponseEntity.noContent().build();
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
