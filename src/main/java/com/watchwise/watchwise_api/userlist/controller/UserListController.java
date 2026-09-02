package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.userlist.dto.UserListBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListDetailedResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListPatchDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListResponseDTO;
import com.watchwise.watchwise_api.userlist.service.UserListService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class UserListController {

    private final UserListService userListService;

    @GetMapping("/users/{userId}/lists")
    public ResponseEntity<PageResponseDTO<UserListResponseDTO>> getUserLists(
            @PathVariable UUID userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) UUID contentId
    ) {
        Page<UserListResponseDTO> lists =
                userListService.getUserLists(getCurrentUserId(), userId, page, size, sortBy, sortDirection, contentId);
        return ResponseEntity.ok(PageResponseDTO.of(lists));
    }

    @GetMapping("/users/me/liked-lists")
    public ResponseEntity<PageResponseDTO<UserListResponseDTO>> getLikedLists(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<UserListResponseDTO> lists = userListService.getLikedLists(getCurrentUserId(), page, size);
        return ResponseEntity.ok(PageResponseDTO.of(lists));
    }

    @PostMapping("/users/me/lists")
    public ResponseEntity<UserListResponseDTO> createUserList(@Valid @RequestBody UserListCreationDTO userListCreationDTO) {
        UserListResponseDTO created = userListService.createUserList(getCurrentUserId(), userListCreationDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/users/me/lists/bulk")
    public ResponseEntity<UserListDetailedResponseDTO> createUserListWithItems(
            @Valid @RequestBody UserListBulkCreationDTO userListBulkCreationDTO
    ) {
        UserListDetailedResponseDTO created = userListService.createUserListWithItems(getCurrentUserId(), userListBulkCreationDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/lists/{listId}")
    public ResponseEntity<UserListDetailedResponseDTO> getUserListById(
            @PathVariable UUID listId,
            @RequestParam(required = false) ContentType type,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection
    ) {
        UserListDetailedResponseDTO list = userListService.getUserListById(getCurrentUserId(), listId, type, genre, sortBy, sortDirection);
        return ResponseEntity.ok(list);
    }

    @PatchMapping("/lists/{listId}")
    public ResponseEntity<UserListResponseDTO> updateUserList(
            @PathVariable UUID listId,
            @Valid @RequestBody UserListPatchDTO userListPatchDTO
    ) {
        UserListResponseDTO updated = userListService.updateUserList(getCurrentUserId(), listId, userListPatchDTO);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/lists/{listId}")
    public ResponseEntity<Void> deleteUserList(@PathVariable UUID listId) {
        userListService.deleteUserList(getCurrentUserId(), listId);
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}