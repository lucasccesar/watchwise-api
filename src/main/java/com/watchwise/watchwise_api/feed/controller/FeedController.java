package com.watchwise.watchwise_api.feed.controller;

import com.watchwise.watchwise_api.common.dto.CursorPageResponseDTO;
import com.watchwise.watchwise_api.feed.dto.FeedItemDTO;
import com.watchwise.watchwise_api.feed.service.FeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;

    @GetMapping("/feed")
    public ResponseEntity<CursorPageResponseDTO<FeedItemDTO>> getFeed(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size
    ) {
        CursorPageResponseDTO<FeedItemDTO> feed = feedService.getFeed(getCurrentUserId(), cursor, size);
        return ResponseEntity.ok(feed);
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
