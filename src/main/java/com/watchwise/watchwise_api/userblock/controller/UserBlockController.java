package com.watchwise.watchwise_api.userblock.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.userblock.dto.BlockedUserDTO;
import com.watchwise.watchwise_api.userblock.service.UserBlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserBlockController {

    private final UserBlockService userBlockService;

    @GetMapping("/me/blocked")
    public ResponseEntity<PageResponseDTO<BlockedUserDTO>> getBlockedUsers(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Page<BlockedUserDTO> blockedUsers = userBlockService.getBlockedUsers(getCurrentUserId(), page, size);
        return ResponseEntity.ok(PageResponseDTO.of(blockedUsers));
    }

    @PostMapping("/me/blocked/{userId}")
    public ResponseEntity<Void> block(@PathVariable UUID userId) {
        userBlockService.block(getCurrentUserId(), userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me/blocked/{userId}")
    public ResponseEntity<Void> unblock(@PathVariable UUID userId) {
        userBlockService.unblock(getCurrentUserId(), userId);
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
