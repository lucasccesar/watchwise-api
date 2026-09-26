package com.watchwise.watchwise_api.contentposter.controller;

import com.watchwise.watchwise_api.contentposter.dto.UserContentPosterRequestDTO;
import com.watchwise.watchwise_api.contentposter.dto.UserContentPosterResponseDTO;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/users/me/content-posters")
@RequiredArgsConstructor
public class UserContentPosterController {

    private final UserContentPosterService userContentPosterService;

    @PutMapping("/{contentId}")
    public ResponseEntity<UserContentPosterResponseDTO> upsert(
            @PathVariable UUID contentId,
            @Valid @RequestBody UserContentPosterRequestDTO request
    ) {
        UserContentPosterResponseDTO response = userContentPosterService.upsert(
                getCurrentUserId(), contentId, request.customPosterUrl());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{contentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID contentId) {
        userContentPosterService.delete(getCurrentUserId(), contentId);
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
