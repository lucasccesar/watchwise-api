package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.user.dto.ProfileHeaderDTO;
import com.watchwise.watchwise_api.user.service.impl.ProfileHeaderReader;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class ProfileHeaderController {
    private final ProfileHeaderReader reader;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.profile-scan.max-requests}")
    private int maxRequests;

    @Value("${app.rate-limit.profile-scan.window-minutes}")
    private long windowMinutes;

    @GetMapping("/profile-header")
    public ProfileHeaderDTO getHeader(@AuthenticationPrincipal UUID viewerId, @RequestParam String username) {
        requestThrottler.checkAllowed("profile-scan|" + viewerId, maxRequests, Duration.ofMinutes(windowMinutes));
        return reader.read(viewerId, username);
    }
}

