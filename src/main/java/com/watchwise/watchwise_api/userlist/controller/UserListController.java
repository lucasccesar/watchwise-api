package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.userlist.dto.UserListBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListDetailedResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListDetailViewResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPageMode;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPageRequest;
import com.watchwise.watchwise_api.userlist.dto.UserListPatchDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListProgressResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListCardDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListViewItemType;
import com.watchwise.watchwise_api.userlist.dto.UserListViewQueryMode;
import com.watchwise.watchwise_api.userlist.dto.UserListViewRequest;
import com.watchwise.watchwise_api.userlist.dto.UserListViewSource;
import com.watchwise.watchwise_api.userlist.service.UserListService;
import com.watchwise.watchwise_api.userlist.service.UserListDetailViewService;
import com.watchwise.watchwise_api.userlist.service.UserListViewService;
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
    private final UserListViewService userListViewService;
    private final UserListDetailViewService userListDetailViewService;

    @GetMapping("/users/me/lists/view")
    public ResponseEntity<PageResponseDTO<UserListCardDTO>> getMyListView(
            @RequestParam(required = false) UserListViewSource source,
            @RequestParam(required = false) UserListViewItemType itemType,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return getListView(new UserListViewRequest(getCurrentUserId(), null, UserListViewQueryMode.MY,
                source, null, itemType, genre, sortBy, sortDirection, page, size));
    }

    @GetMapping("/users/{userId}/lists/view")
    public ResponseEntity<PageResponseDTO<UserListCardDTO>> getUserListView(
            @PathVariable UUID userId,
            @RequestParam(required = false) UserListViewItemType itemType,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return getListView(new UserListViewRequest(getCurrentUserId(), userId, UserListViewQueryMode.USER,
                null, null, itemType, genre, sortBy, sortDirection, page, size));
    }

    @GetMapping("/lists/discover/view")
    public ResponseEntity<PageResponseDTO<UserListCardDTO>> getDiscoverListView(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UserListViewItemType itemType,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return getListView(new UserListViewRequest(getCurrentUserId(), null, UserListViewQueryMode.DISCOVER,
                null, q, itemType, genre, sortBy, sortDirection, page, size));
    }

    private ResponseEntity<PageResponseDTO<UserListCardDTO>> getListView(UserListViewRequest request) {
        return ResponseEntity.ok(PageResponseDTO.of(userListViewService.getView(request)));
    }

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

    @GetMapping("/lists/discover")
    public ResponseEntity<PageResponseDTO<UserListResponseDTO>> getDiscoverLists(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<UserListResponseDTO> lists = userListService.getDiscoverLists(getCurrentUserId(), page, size);
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
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        UserListDetailedResponseDTO list = userListService.getUserListById(
                getCurrentUserId(), listId, type, genre, sortBy, sortDirection, page, size);
        return ResponseEntity.ok(list);
    }

    @GetMapping("/lists/{listId}/view")
    public ResponseEntity<UserListDetailViewResponseDTO> getUserListDetailView(
            @PathVariable UUID listId,
            @RequestParam(required = false) ContentType type,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) WatchStatus watchStatus,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) UserListItemPageMode mode
    ) {
        if (mode != null && mode != UserListItemPageMode.VISUAL) {
            throw new BadRequestException("List detail view only supports VISUAL mode");
        }
        UserListItemPageRequest request = new UserListItemPageRequest(
                getCurrentUserId(), listId, type, genre, sortBy, sortDirection, watchStatus, page, size,
                UserListItemPageMode.VISUAL);
        return ResponseEntity.ok(userListDetailViewService.getView(request));
    }

    @GetMapping("/lists/{listId}/progress")
    public ResponseEntity<UserListProgressResponseDTO> getUserListProgress(@PathVariable UUID listId) {
        UserListProgressResponseDTO progress = userListService.getUserListProgress(getCurrentUserId(), listId);
        return ResponseEntity.ok(progress);
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
