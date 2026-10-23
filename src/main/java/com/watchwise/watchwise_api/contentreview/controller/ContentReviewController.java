package com.watchwise.watchwise_api.contentreview.controller;

import com.watchwise.watchwise_api.comment.dto.CommentCreationDTO;
import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.service.CommentService;
import com.watchwise.watchwise_api.contentreview.dto.ContentReviewCreationDTO;
import com.watchwise.watchwise_api.contentreview.dto.ContentReviewPatchDTO;
import com.watchwise.watchwise_api.contentreview.dto.ContentReviewViewDTO;
import com.watchwise.watchwise_api.contentreview.service.ContentReviewService;
import com.watchwise.watchwise_api.like.service.LikeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.domain.Page;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ContentReviewController {

    private final ContentReviewService contentReviewService;
    private final LikeService likeService;
    private final CommentService commentService;

    @PostMapping("/contents/{contentId}/reviews")
    public ResponseEntity<ContentReviewViewDTO> create(
            @PathVariable UUID contentId,
            @Valid @RequestBody ContentReviewCreationDTO request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contentReviewService.create(
                currentUserId(), contentId, request, idempotencyKey));
    }

    @GetMapping("/reviews/{reviewId}")
    public ResponseEntity<ContentReviewViewDTO> get(@PathVariable UUID reviewId) {
        return ResponseEntity.ok(contentReviewService.get(currentUserId(), reviewId));
    }

    @PatchMapping("/reviews/{reviewId}")
    public ResponseEntity<ContentReviewViewDTO> edit(
            @PathVariable UUID reviewId,
            @Valid @RequestBody ContentReviewPatchDTO request) {
        return ResponseEntity.ok(contentReviewService.edit(currentUserId(), reviewId, request));
    }

    @DeleteMapping("/reviews/{reviewId}")
    public ResponseEntity<Void> delete(@PathVariable UUID reviewId) {
        contentReviewService.delete(currentUserId(), reviewId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reviews/{reviewId}/like")
    public ResponseEntity<Void> like(@PathVariable UUID reviewId) {
        likeService.likeContentReview(currentUserId(), reviewId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/reviews/{reviewId}/like")
    public ResponseEntity<Void> unlike(@PathVariable UUID reviewId) {
        likeService.unlikeContentReview(currentUserId(), reviewId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/reviews/{reviewId}/comments")
    public ResponseEntity<Page<CommentResponseDTO>> getComments(
            @PathVariable UUID reviewId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(commentService.getCommentsForContentReview(
                currentUserId(), reviewId, page, size));
    }

    @PostMapping("/reviews/{reviewId}/comments")
    public ResponseEntity<CommentResponseDTO> createComment(
            @PathVariable UUID reviewId,
            @Valid @RequestBody CommentCreationDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(commentService.createCommentOnContentReview(
                currentUserId(), reviewId, request));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
