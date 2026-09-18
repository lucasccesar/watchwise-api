package com.watchwise.watchwise_api.like.controller;

import com.watchwise.watchwise_api.like.service.LikeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class LikeController {

    private final LikeService likeService;

    @PostMapping("/comments/{commentId}/like")
    public ResponseEntity<Void> likeComment(@PathVariable UUID commentId) {
        likeService.likeComment(getCurrentUserId(), commentId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/comments/{commentId}/like")
    public ResponseEntity<Void> unlikeComment(@PathVariable UUID commentId) {
        likeService.unlikeComment(getCurrentUserId(), commentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/diary/{diaryEntryId}/like")
    public ResponseEntity<Void> likeDiaryEntry(@PathVariable UUID diaryEntryId) {
        likeService.likeDiaryEntry(getCurrentUserId(), diaryEntryId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/diary/{diaryEntryId}/like")
    public ResponseEntity<Void> unlikeDiaryEntry(@PathVariable UUID diaryEntryId) {
        likeService.unlikeDiaryEntry(getCurrentUserId(), diaryEntryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/lists/{listId}/like")
    public ResponseEntity<Void> likeList(@PathVariable UUID listId) {
        likeService.likeList(getCurrentUserId(), listId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/lists/{listId}/like")
    public ResponseEntity<Void> unlikeList(@PathVariable UUID listId) {
        likeService.unlikeList(getCurrentUserId(), listId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/picks/{pickId}/like")
    public ResponseEntity<Void> likePick(@PathVariable UUID pickId) {
        likeService.likePick(getCurrentUserId(), pickId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/picks/{pickId}/like")
    public ResponseEntity<Void> unlikePick(@PathVariable UUID pickId) {
        likeService.unlikePick(getCurrentUserId(), pickId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/picks-templates/{templateId}/like")
    public ResponseEntity<Void> likePicksTemplate(@PathVariable UUID templateId) {
        likeService.likePicksTemplate(getCurrentUserId(), templateId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/picks-templates/{templateId}/like")
    public ResponseEntity<Void> unlikePicksTemplate(@PathVariable UUID templateId) {
        likeService.unlikePicksTemplate(getCurrentUserId(), templateId);
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
