package com.watchwise.watchwise_api.followedperson.controller;

import com.watchwise.watchwise_api.followedperson.service.FollowedPersonService;
import lombok.RequiredArgsConstructor;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class FollowedPersonController {

    private final FollowedPersonService followedPersonService;

    @PostMapping("/me/follow-people/{personTmdbId}")
    public ResponseEntity<Void> followPerson(@PathVariable String personTmdbId) {
        followedPersonService.followPerson(getCurrentUserId(), personTmdbId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me/follow-people/{personTmdbId}")
    public ResponseEntity<Void> unfollowPerson(@PathVariable String personTmdbId) {
        followedPersonService.unfollowPerson(getCurrentUserId(), personTmdbId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}/follow-people")
    public ResponseEntity<List<String>> getFollowedPeople(
            @PathVariable UUID userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<String> followedPeople = followedPersonService.getFollowedPeople(getCurrentUserId(), userId, page, size);
        return ResponseEntity.ok(followedPeople.getContent());
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}