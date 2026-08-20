package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.userlist.dto.UserListItemBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.mapper.UserListItemMapper;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserListItemServiceImpl implements UserListItemService {

    private final UserListItemRepository userListItemRepository;
    private final UserListRepository userListRepository;
    private final ContentRepository contentRepository;
    private final ContentService contentService;
    private final FollowerRepository followerRepository;
    private final UserListItemMapper userListItemMapper;

    @Override
    public List<UserListItemResponseDTO> getItems(UUID listId) {
        return userListItemRepository.findByUserListIdOrderByPositionAsc(listId).stream()
                .map(userListItemMapper::userListItemToResponseDto)
                .toList();
    }

    @Override
    public List<ContentRefDTO> getPreviewItems(UUID listId) {
        return userListItemRepository.findTop5ByUserListIdAndContentIdIsNotNullOrderByPositionAsc(listId).stream()
                .map(userListItemMapper::userListItemToResponseDto)
                .map(UserListItemResponseDTO::content)
                .toList();
    }

    @Override
    public long countNestedLists(UUID listId) {
        return userListItemRepository.countByUserListIdAndChildListIdIsNotNull(listId);
    }

    @Override
    public double getWatchedPercentage(UUID listId, UUID ownerId) {
        long totalContentItems = userListItemRepository.countByUserListIdAndContentIdIsNotNull(listId);
        if (totalContentItems == 0) {
            return 0.0;
        }

        long watchedContentItems = userListItemRepository.countWatchedContentItems(listId, ownerId);
        return (watchedContentItems * 100.0) / totalContentItems;
    }

    @Override
    @Transactional
    public UserListItemResponseDTO addItem(UUID userId, UUID listId, UserListItemCreationDTO userListItemCreationDTO) {
        UserList userList = findOwnedList(userId, listId);
        validateExactlyOneTarget(userListItemCreationDTO);

        LocalDateTime now = LocalDateTime.now();
        UserListItem.UserListItemBuilder builder = UserListItem.builder()
                .userList(userList)
                .description(userListItemCreationDTO.description())
                .createdAt(now)
                .updatedAt(now);

        if (userListItemCreationDTO.content() != null) {
            assertListIsNotLockedAsListOfLists(listId);
            ContentRefDTO contentRef = contentService.getOrCreateReference(userListItemCreationDTO.content());
            builder.content(contentRepository.getReferenceById(contentRef.id()));
        } else {
            assertListIsNotLockedAsContentList(listId);
            builder.childList(resolveChildList(userId, listId, userListItemCreationDTO.childListId()));
        }

        UserListItem newItem = builder.build();

        try {
            UserListItem saved = insertAtPosition(listId, newItem, userListItemCreationDTO.position());
            return userListItemMapper.userListItemToResponseDto(saved);
        } catch (DataIntegrityViolationException e) {
            throw mapUniqueConstraintViolation(e);
        }
    }

    @Override
    @Transactional
    public List<UserListItemResponseDTO> addItems(UUID userId, UUID listId, UserListItemBulkCreationDTO userListItemBulkCreationDTO) {
        findOwnedList(userId, listId);

        return userListItemBulkCreationDTO.items().stream()
                .map(content -> addItem(userId, listId, new UserListItemCreationDTO(content, null, null, null)))
                .toList();
    }

    @Override
    @Transactional
    public void removeItem(UUID userId, UUID listId, UUID itemId) {
        findOwnedList(userId, listId);

        UserListItem item = userListItemRepository.findById(itemId)
                .orElseThrow(() -> new NotFoundException("List item not found"));

        if (!item.getUserList().getId().equals(listId)) {
            throw new NotFoundException("List item not found");
        }

        deleteAndCloseGap(item);
    }

    private void validateExactlyOneTarget(UserListItemCreationDTO userListItemCreationDTO) {
        boolean hasContent = userListItemCreationDTO.content() != null;
        boolean hasChildList = userListItemCreationDTO.childListId() != null;

        if (hasContent == hasChildList) {
            throw new BadRequestException("Exactly one of content or childListId must be provided");
        }
    }

    private UserList resolveChildList(UUID userId, UUID parentListId, UUID childListId) {
        UserList childList = userListRepository.findById(childListId)
                .orElseThrow(() -> new NotFoundException("List not found"));

        if (childListId.equals(parentListId)) {
            throw new BadRequestException("A list cannot reference itself");
        }

        assertListIsVisibleTo(userId, childList);

        if (userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(childListId)) {
            throw new BadRequestException("The referenced list already contains nested lists; nesting depth is limited to one level");
        }

        return childList;
    }

    private void assertListIsVisibleTo(UUID viewerId, UserList list) {
        UUID ownerId = list.getUser().getId();

        if (viewerId.equals(ownerId) || list.getVisibility() == UserListVisibility.PUBLIC) {
            return;
        }

        if (list.getVisibility() == UserListVisibility.FOLLOWERS
                && followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, ownerId, FollowStatus.ACCEPTED)) {
            return;
        }

        throw new ForbiddenException("This list is private");
    }

    private void assertListIsNotLockedAsListOfLists(UUID listId) {
        if (userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)) {
            throw new BadRequestException("This list already contains nested lists and cannot also contain content items");
        }
    }

    private void assertListIsNotLockedAsContentList(UUID listId) {
        if (userListItemRepository.existsByUserListIdAndContentIdIsNotNull(listId)) {
            throw new BadRequestException("This list already contains content items and cannot also contain nested lists");
        }
    }

    private UserListItem insertAtPosition(UUID listId, UserListItem newItem, Integer requestedPosition) {
        List<UserListItem> existing = userListItemRepository.findByUserListIdOrderByPositionAsc(listId);
        int currentCount = existing.size();
        int targetPosition = requestedPosition != null ? requestedPosition : currentCount + 1;

        if (targetPosition > currentCount + 1) {
            throw new BadRequestException("position cannot be greater than " + (currentCount + 1) + ", the next free position in the list");
        }

        List<UserListItem> toShift = existing.stream()
                .filter(item -> item.getPosition() >= targetPosition)
                .sorted(Comparator.comparing(UserListItem::getPosition).reversed())
                .toList();

        for (UserListItem item : toShift) {
            item.setPosition(item.getPosition() + 1);
            userListItemRepository.save(item);
            userListItemRepository.flush();
        }

        newItem.setPosition(targetPosition);
        UserListItem saved = userListItemRepository.save(newItem);
        userListItemRepository.flush();
        return saved;
    }

    private void deleteAndCloseGap(UserListItem item) {
        UUID listId = item.getUserList().getId();
        int removedPosition = item.getPosition();
        userListItemRepository.delete(item);
        userListItemRepository.flush();

        List<UserListItem> toShift = userListItemRepository.findByUserListIdOrderByPositionAsc(listId).stream()
                .filter(remaining -> remaining.getPosition() > removedPosition)
                .toList();

        for (UserListItem remaining : toShift) {
            remaining.setPosition(remaining.getPosition() - 1);
            userListItemRepository.save(remaining);
            userListItemRepository.flush();
        }
    }

    private UserList findOwnedList(UUID userId, UUID listId) {
        UserList userList = userListRepository.findById(listId)
                .orElseThrow(() -> new NotFoundException("List not found"));

        if (!userList.getUser().getId().equals(userId)) {
            throw new NotFoundException("List not found");
        }

        return userList;
    }

    private ConflictException mapUniqueConstraintViolation(DataIntegrityViolationException e) {
        String constraintName = extractConstraintName(e);

        if ("uq_user_list_items_user_list_id_content_id".equals(constraintName)) {
            return new ConflictException("This content is already in the list");
        }
        if ("uq_user_list_items_user_list_id_child_list_id".equals(constraintName)) {
            return new ConflictException("This list is already nested in the list");
        }
        if ("uq_user_list_items_user_list_id_position".equals(constraintName)) {
            return new ConflictException("This position was just taken by a concurrent insert");
        }
        return new ConflictException("Unable to insert this item into the list");
    }

    private String extractConstraintName(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
            return cve.getConstraintName();
        }
        return null;
    }
}