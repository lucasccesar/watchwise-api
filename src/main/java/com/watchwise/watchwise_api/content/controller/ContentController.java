package com.watchwise.watchwise_api.content.controller;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.service.ContentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/contents")
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;

    @PostMapping("/reference")
    public ResponseEntity<ContentRefDTO> getOrCreateReference(@Valid @RequestBody ContentRefCreationDTO contentRefCreationDTO) {
        ContentRefDTO contentRefDTO = contentService.getOrCreateReference(contentRefCreationDTO);
        return ResponseEntity.ok(contentRefDTO);
    }

}