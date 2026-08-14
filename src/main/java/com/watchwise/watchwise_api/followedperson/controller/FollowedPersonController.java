package com.watchwise.watchwise_api.followedperson.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.followedperson.service.FollowedPersonService;
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
public class FollowedPersonController {

    private final FollowedPersonService followedPersonService;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.follow-people-action.max-requests}")
    private int followPeopleActionMaxRequests;
    @Value("${app.rate-limit.follow-people-action.window-minutes}")
    private long followPeopleActionWindowMinutes;

    @PostMapping("/me/follow-people/{personTmdbId}")
    public ResponseEntity<Void> followPerson(@PathVariable String personTmdbId) {
        requestThrottler.checkAllowed(followPeopleActionKey(), followPeopleActionMaxRequests, Duration.ofMinutes(followPeopleActionWindowMinutes));

        followedPersonService.followPerson(getCurrentUserId(), personTmdbId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me/follow-people/{personTmdbId}")
    public ResponseEntity<Void> unfollowPerson(@PathVariable String personTmdbId) {
        requestThrottler.checkAllowed(followPeopleActionKey(), followPeopleActionMaxRequests, Duration.ofMinutes(followPeopleActionWindowMinutes));

        followedPersonService.unfollowPerson(getCurrentUserId(), personTmdbId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}/follow-people")
    public ResponseEntity<PageResponseDTO<String>> getFollowedPeople(
            @PathVariable UUID userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<String> followedPeople = followedPersonService.getFollowedPeople(getCurrentUserId(), userId, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(followedPeople));
    }

    private String followPeopleActionKey() {
        return "follow-people-action|" + getCurrentUserId();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}