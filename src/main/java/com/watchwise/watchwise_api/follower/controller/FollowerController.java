package com.watchwise.watchwise_api.follower.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.follower.dto.FollowStatusResponseDTO;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.service.FollowerService;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class FollowerController {

    private final FollowerService followerService;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.follow-action.max-requests}")
    private int followActionMaxRequests;
    @Value("${app.rate-limit.follow-action.window-minutes}")
    private long followActionWindowMinutes;

    @GetMapping("/{userId}/followers")
    public ResponseEntity<PageResponseDTO<PublicUserDTO>> getFollowers(
            @PathVariable UUID userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<PublicUserDTO> followers = followerService.getFollowers(getCurrentUserId(), userId, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(followers));
    }

    @GetMapping("/{userId}/following")
    public ResponseEntity<PageResponseDTO<PublicUserDTO>> getFollowing(
            @PathVariable UUID userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<PublicUserDTO> following = followerService.getFollowing(getCurrentUserId(), userId, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(following));
    }

    @PostMapping("/{userId}/follow")
    public ResponseEntity<FollowStatusResponseDTO> followUser(@PathVariable UUID userId) {
        requestThrottler.checkAllowed(followActionKey(), followActionMaxRequests, Duration.ofMinutes(followActionWindowMinutes));

        FollowStatus status = followerService.followUser(getCurrentUserId(), userId);
        return ResponseEntity.ok(new FollowStatusResponseDTO(status));
    }

    @DeleteMapping("/{userId}/follow")
    public ResponseEntity<Void> unfollowUser(@PathVariable UUID userId) {
        requestThrottler.checkAllowed(followActionKey(), followActionMaxRequests, Duration.ofMinutes(followActionWindowMinutes));

        followerService.unfollowUser(getCurrentUserId(), userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/follow-requests")
    public ResponseEntity<PageResponseDTO<PublicUserDTO>> getPendingFollowRequests(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<PublicUserDTO> requests = followerService.getPendingFollowRequests(getCurrentUserId(), page, size);
        return ResponseEntity.ok(PageResponseDTO.of(requests));
    }

    @PostMapping("/me/follow-requests/{requesterId}/accept")
    public ResponseEntity<Void> acceptFollowRequest(@PathVariable UUID requesterId) {
        followerService.acceptFollowRequest(getCurrentUserId(), requesterId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me/follow-requests/{requesterId}")
    public ResponseEntity<Void> rejectFollowRequest(@PathVariable UUID requesterId) {
        followerService.rejectFollowRequest(getCurrentUserId(), requesterId);
        return ResponseEntity.noContent().build();
    }

    private String followActionKey() {
        return "follow-action|" + getCurrentUserId();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
