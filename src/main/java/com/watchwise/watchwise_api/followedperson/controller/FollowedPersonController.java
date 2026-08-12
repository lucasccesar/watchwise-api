package com.watchwise.watchwise_api.followedperson.controller;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.followedperson.service.FollowedPersonService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/users/{userId}/follow-people")
@RequiredArgsConstructor
public class FollowedPersonController {

    private final FollowedPersonService followedPersonService;

    @PostMapping("/{personTmdbId}")
    public ResponseEntity<Void> followPerson(@PathVariable UUID userId, @PathVariable String personTmdbId) {
        assertIsCurrentUser(userId);
        followedPersonService.followPerson(userId, personTmdbId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{personTmdbId}")
    public ResponseEntity<Void> unfollowPerson(@PathVariable UUID userId, @PathVariable String personTmdbId) {
        assertIsCurrentUser(userId);
        followedPersonService.unfollowPerson(userId, personTmdbId);
        return ResponseEntity.noContent().build();
    }

    private void assertIsCurrentUser(UUID userId) {
        if (!getCurrentUserId().equals(userId)) {
            throw new ForbiddenException("Cannot manage another user's followed people");
        }
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}