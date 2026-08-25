package com.watchwise.watchwise_api.comment.controller;

import com.watchwise.watchwise_api.comment.dto.CommentCreationDTO;
import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.service.CommentService;
import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
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
public class CommentController {

    private final CommentService commentService;

    @GetMapping("/contents/{contentId}/comments")
    public ResponseEntity<PageResponseDTO<CommentResponseDTO>> getCommentsForContent(
            @PathVariable UUID contentId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<CommentResponseDTO> comments = commentService.getCommentsForContent(getCurrentUserId(), contentId, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(comments));
    }

    @PostMapping("/contents/{contentId}/comments")
    public ResponseEntity<CommentResponseDTO> createCommentOnContent(
            @PathVariable UUID contentId,
            @Valid @RequestBody CommentCreationDTO commentCreationDTO
    ) {
        CommentResponseDTO created = commentService.createCommentOnContent(getCurrentUserId(), contentId, commentCreationDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/lists/{listId}/comments")
    public ResponseEntity<PageResponseDTO<CommentResponseDTO>> getCommentsForList(
            @PathVariable UUID listId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<CommentResponseDTO> comments = commentService.getCommentsForList(getCurrentUserId(), listId, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(comments));
    }

    @PostMapping("/lists/{listId}/comments")
    public ResponseEntity<CommentResponseDTO> createCommentOnList(
            @PathVariable UUID listId,
            @Valid @RequestBody CommentCreationDTO commentCreationDTO
    ) {
        CommentResponseDTO created = commentService.createCommentOnList(getCurrentUserId(), listId, commentCreationDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/diary/{diaryEntryId}/comments")
    public ResponseEntity<PageResponseDTO<CommentResponseDTO>> getCommentsForDiaryEntry(
            @PathVariable UUID diaryEntryId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<CommentResponseDTO> comments = commentService.getCommentsForDiaryEntry(getCurrentUserId(), diaryEntryId, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(comments));
    }

    @PostMapping("/diary/{diaryEntryId}/comments")
    public ResponseEntity<CommentResponseDTO> createCommentOnDiaryEntry(
            @PathVariable UUID diaryEntryId,
            @Valid @RequestBody CommentCreationDTO commentCreationDTO
    ) {
        CommentResponseDTO created = commentService.createCommentOnDiaryEntry(getCurrentUserId(), diaryEntryId, commentCreationDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(@PathVariable UUID commentId) {
        commentService.deleteComment(getCurrentUserId(), commentId);
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
