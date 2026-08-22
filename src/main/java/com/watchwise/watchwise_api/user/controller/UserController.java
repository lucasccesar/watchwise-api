package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.security.AttemptLockout;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.user.dto.DeleteAccountDTO;
import com.watchwise.watchwise_api.user.dto.PatchUserDTO;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final CookieUtil cookieUtil;
    private final AttemptLockout attemptLockout;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.delete-account.max-attempts}")
    private int deleteAccountMaxAttempts;
    @Value("${app.rate-limit.delete-account.window-minutes}")
    private long deleteAccountWindowMinutes;
    @Value("${app.rate-limit.delete-account.block-minutes}")
    private long deleteAccountBlockMinutes;

    @Value("${app.rate-limit.patch-account.max-attempts}")
    private int patchAccountMaxAttempts;
    @Value("${app.rate-limit.patch-account.window-minutes}")
    private long patchAccountWindowMinutes;
    @Value("${app.rate-limit.patch-account.block-minutes}")
    private long patchAccountBlockMinutes;

    @Value("${app.rate-limit.profile-scan.max-requests}")
    private int profileScanMaxRequests;
    @Value("${app.rate-limit.profile-scan.window-minutes}")
    private long profileScanWindowMinutes;

    @GetMapping
    public ResponseEntity<PageResponseDTO<UserPreviewDTO>> getUsersByUsername(
            @RequestParam String username,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        requestThrottler.checkAllowed(profileScanKey(), profileScanMaxRequests, Duration.ofMinutes(profileScanWindowMinutes));

        Page<UserPreviewDTO> users = userService.getUsersByUsername(username, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(users));
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponseDTO> getCurrentUser() {
        UserResponseDTO user = userService.getCurrentUser(getCurrentUserId());
        return ResponseEntity.ok(user);
    }

    @PatchMapping("/me")
    public ResponseEntity<UserResponseDTO> updateCurrentUser(@Valid @RequestBody PatchUserDTO patchUserDTO) {
        UUID currentUserId = getCurrentUserId();
        UserService.CredentialCheck credentialCheck = userService.checkCredentialChanges(currentUserId, patchUserDTO);
        boolean touchesCredentials = credentialCheck.touchesCredentials();
        String lockoutKey = lockoutKey("patch-account", currentUserId);

        if (touchesCredentials) {
            attemptLockout.checkAllowed(lockoutKey, patchAccountMaxAttempts, Duration.ofMinutes(patchAccountWindowMinutes));
        }

        UserResponseDTO user;
        try {
            user = userService.updateUser(credentialCheck.user(), patchUserDTO);
        } catch (UnauthorizedException e) {
            if (touchesCredentials) {
                attemptLockout.recordFailure(lockoutKey, patchAccountMaxAttempts, Duration.ofMinutes(patchAccountBlockMinutes));
            }
            throw e;
        }
        if (touchesCredentials) {
            attemptLockout.recordSuccess(lockoutKey);
        }

        return ResponseEntity.ok(user);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<PublicUserDTO> getUserById(@PathVariable UUID userId) {
        requestThrottler.checkAllowed(profileScanKey(), profileScanMaxRequests, Duration.ofMinutes(profileScanWindowMinutes));

        PublicUserDTO user = userService.getUserById(userId);
        return ResponseEntity.ok(user);
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteCurrentUser(
            @Valid @RequestBody DeleteAccountDTO deleteAccountDTO,
            HttpServletResponse response
    ) {
        UUID currentUserId = getCurrentUserId();
        String lockoutKey = lockoutKey("delete-account", currentUserId);
        attemptLockout.checkAllowed(lockoutKey, deleteAccountMaxAttempts, Duration.ofMinutes(deleteAccountWindowMinutes));

        try {
            userService.deleteAccount(currentUserId, deleteAccountDTO);
        } catch (UnauthorizedException e) {
            attemptLockout.recordFailure(lockoutKey, deleteAccountMaxAttempts, Duration.ofMinutes(deleteAccountBlockMinutes));
            throw e;
        }
        attemptLockout.recordSuccess(lockoutKey);

        cookieUtil.addCookie(response, cookieUtil.clearCookie(CookieUtil.ACCESS_TOKEN_COOKIE, "/"));
        cookieUtil.addCookie(response, cookieUtil.clearCookie(CookieUtil.REFRESH_TOKEN_COOKIE, cookieUtil.getRefreshTokenPath()));
        cookieUtil.addCookie(response, cookieUtil.clearCookie(CookieUtil.CSRF_TOKEN_COOKIE, cookieUtil.getCsrfTokenPath()));

        return ResponseEntity.noContent().build();
    }

    private String lockoutKey(String action, UUID userId) {
        return action + "|" + userId;
    }

    private String profileScanKey() {
        return "profile-scan|" + getCurrentUserId();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
