package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.user.dto.DeleteAccountDTO;
import com.watchwise.watchwise_api.user.dto.PatchUserDTO;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final CookieUtil cookieUtil;

    @GetMapping
    public ResponseEntity<List<UserPreviewDTO>> getUsersByUsername(
            @RequestParam String username,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<UserPreviewDTO> users = userService.getUsersByUsername(username, page, size, null);
        return ResponseEntity.ok(users.getContent());
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponseDTO> getCurrentUser() {
        UserResponseDTO user = userService.getCurrentUser(getCurrentUserId());
        return ResponseEntity.ok(user);
    }

    @PatchMapping("/me")
    public ResponseEntity<UserResponseDTO> updateCurrentUser(@Valid @RequestBody PatchUserDTO patchUserDTO) {
        UserResponseDTO user = userService.updateUser(getCurrentUserId(), patchUserDTO);
        return ResponseEntity.ok(user);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<PublicUserDTO> getUserById(@PathVariable UUID userId) {
        PublicUserDTO user = userService.getUserById(userId);
        return ResponseEntity.ok(user);
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteCurrentUser(
            @Valid @RequestBody DeleteAccountDTO deleteAccountDTO,
            HttpServletResponse response
    ) {
        userService.deleteAccount(getCurrentUserId(), deleteAccountDTO);

        cookieUtil.addCookie(response, cookieUtil.clearCookie(CookieUtil.ACCESS_TOKEN_COOKIE, "/"));
        cookieUtil.addCookie(response, cookieUtil.clearCookie(CookieUtil.REFRESH_TOKEN_COOKIE, CookieUtil.REFRESH_TOKEN_PATH));
        cookieUtil.addCookie(response, cookieUtil.clearCookie(CookieUtil.CSRF_TOKEN_COOKIE, "/"));

        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
