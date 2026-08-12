package com.watchwise.watchwise_api.followedperson.controller;

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
@RequestMapping("/users/me/follow-people")
@RequiredArgsConstructor
public class FollowedPersonController {

    private final FollowedPersonService followedPersonService;

    @PostMapping("/{personTmdbId}")
    public ResponseEntity<Void> followPerson(@PathVariable String personTmdbId) {
        followedPersonService.followPerson(getCurrentUserId(), personTmdbId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{personTmdbId}")
    public ResponseEntity<Void> unfollowPerson(@PathVariable String personTmdbId) {
        followedPersonService.unfollowPerson(getCurrentUserId(), personTmdbId);
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}