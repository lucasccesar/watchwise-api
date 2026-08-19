package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise.watchwise_api.userlist.dto.UserListItemCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/lists")
@RequiredArgsConstructor
public class UserListItemController {

    private final UserListItemService userListItemService;

    @PostMapping("/{listId}/items")
    public ResponseEntity<UserListItemResponseDTO> addItem(
            @PathVariable UUID listId,
            @Valid @RequestBody UserListItemCreationDTO userListItemCreationDTO
    ) {
        UserListItemResponseDTO created = userListItemService.addItem(getCurrentUserId(), listId, userListItemCreationDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{listId}/items/{itemId}")
    public ResponseEntity<Void> removeItem(@PathVariable UUID listId, @PathVariable UUID itemId) {
        userListItemService.removeItem(getCurrentUserId(), listId, itemId);
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
