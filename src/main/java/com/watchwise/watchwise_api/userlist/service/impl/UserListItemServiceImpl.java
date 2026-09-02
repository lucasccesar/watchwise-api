package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.userlist.dto.UserListItemBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPatchDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.mapper.UserListItemMapper;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserListItemServiceImpl implements UserListItemService {

    private final UserListItemRepository userListItemRepository;
    private final UserListRepository userListRepository;
    private final ContentRepository contentRepository;
    private final ContentService contentService;
    private final FollowerRepository followerRepository;
    private final UserListItemMapper userListItemMapper;

    static final int POSITION_PARK_OFFSET = 1_000_000_000;

    private UserListItemScope resolveExistingContentScope(UUID listId) {
        return UserListItemScope.resolve(userListItemRepository.findDistinctContentTypesByUserListId(listId), false);
    }

    private void assertContentTypeGroupMatches(UserListItemScope lockedScope, ContentType candidateType) {
        UserListItemScope candidateScope = UserListItemScope.forContentType(candidateType);
        if (lockedScope != null && lockedScope != candidateScope) {
            throw new BadRequestException(
                    "This list already contains items of a different content type group and cannot also contain " + candidateType);
        }
    }

    @Override
    public List<UserListItemResponseDTO> getItems(UUID viewerId, UUID listId) {
        return userListItemRepository.findByUserListIdWithContentAndChildListOrderByPositionAsc(listId).stream()
                .map(item -> toVisibilityScopedResponseDto(viewerId, item))
                .toList();
    }

    private UserListItemResponseDTO toVisibilityScopedResponseDto(UUID viewerId, UserListItem item) {
        UserListItemResponseDTO dto = userListItemMapper.userListItemToResponseDto(item);

        if (item.getChildList() != null && !isVisibleTo(viewerId, item.getChildList())) {
            return new UserListItemResponseDTO(
                    dto.id(), dto.content(), null, dto.position(), dto.description(), dto.createdAt(), dto.updatedAt(),
                    dto.customPosterUrl());
        }

        return dto;
    }

    @Override
    public List<ContentRefDTO> getPreviewItems(UUID listId) {
        return getPreviewItemsByListIds(List.of(listId)).getOrDefault(listId, List.of());
    }

    @Override
    public long countNestedLists(UUID listId) {
        return countNestedListsByListIds(List.of(listId)).getOrDefault(listId, 0L);
    }

    @Override
    public double getWatchedPercentage(UUID listId, UUID ownerId) {
        return getWatchedPercentagesByListIds(List.of(listId), ownerId).getOrDefault(listId, 0.0);
    }

    @Override
    public Map<UUID, List<ContentRefDTO>> getPreviewItemsByListIds(Collection<UUID> listIds) {
        if (listIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, List<ContentRefDTO>> previewsByListId = new LinkedHashMap<>();
        for (UserListItem item : userListItemRepository.findContentItemsByUserListIdInOrderByPosition(listIds)) {
            List<ContentRefDTO> previews = previewsByListId.computeIfAbsent(
                    item.getUserList().getId(), id -> new ArrayList<>());
            if (previews.size() < 5) {
                previews.add(userListItemMapper.userListItemToResponseDto(item).content());
            }
        }
        return previewsByListId;
    }

    @Override
    public Map<UUID, Long> countNestedListsByListIds(Collection<UUID> listIds) {
        if (listIds.isEmpty()) {
            return Map.of();
        }

        return userListItemRepository.countNestedListsByUserListIdIn(listIds).stream()
                .collect(Collectors.toMap(
                        UserListItemRepository.UserListCount::getUserListId,
                        UserListItemRepository.UserListCount::getCount));
    }

    @Override
    public Map<UUID, Double> getWatchedPercentagesByListIds(Collection<UUID> listIds, UUID ownerId) {
        if (listIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Long> totalCountsByListId = userListItemRepository.countContentItemsByUserListIdIn(listIds).stream()
                .collect(Collectors.toMap(
                        UserListItemRepository.UserListCount::getUserListId,
                        UserListItemRepository.UserListCount::getCount));
        if (totalCountsByListId.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Long> watchedCountsByListId = userListItemRepository.countWatchedContentItemsByUserListIdIn(listIds, ownerId).stream()
                .collect(Collectors.toMap(
                        UserListItemRepository.UserListCount::getUserListId,
                        UserListItemRepository.UserListCount::getCount));

        Map<UUID, Double> percentagesByListId = new LinkedHashMap<>();
        totalCountsByListId.forEach((listId, totalCount) -> {
            long watchedCount = watchedCountsByListId.getOrDefault(listId, 0L);
            percentagesByListId.put(listId, (watchedCount * 100.0) / totalCount);
        });
        return percentagesByListId;
    }

    @Override
    public long getItemsCount(UUID listId) {
        return getItemsCountByListIds(List.of(listId)).getOrDefault(listId, 0L);
    }

    @Override
    public Map<UUID, Long> getItemsCountByListIds(Collection<UUID> listIds) {
        if (listIds.isEmpty()) {
            return Map.of();
        }

        return userListItemRepository.countAllItemsByUserListIdIn(listIds).stream()
                .collect(Collectors.toMap(
                        UserListItemRepository.UserListCount::getUserListId,
                        UserListItemRepository.UserListCount::getCount));
    }

    @Override
    public long getTotalRuntimeMinutes(UUID listId) {
        return getTotalRuntimeMinutesByListIds(List.of(listId)).getOrDefault(listId, 0L);
    }

    @Override
    public Map<UUID, Long> getTotalRuntimeMinutesByListIds(Collection<UUID> listIds) {
        if (listIds.isEmpty()) {
            return Map.of();
        }

        return userListItemRepository.sumRuntimeMinutesByUserListIdIn(listIds).stream()
                .collect(Collectors.toMap(
                        UserListItemRepository.UserListSum::getUserListId,
                        UserListItemRepository.UserListSum::getTotal));
    }

    @Override
    public UserListItemScope getItemScope(UUID listId) {
        return getItemScopeByListIds(List.of(listId)).get(listId);
    }

    @Override
    public Map<UUID, UserListItemScope> getItemScopeByListIds(Collection<UUID> listIds) {
        if (listIds.isEmpty()) {
            return Map.of();
        }

        return getItemScopeByListIds(listIds, countNestedListsByListIds(listIds));
    }

    @Override
    public Map<UUID, UserListItemScope> getItemScopeByListIds(Collection<UUID> listIds, Map<UUID, Long> nestedListsCountByListId) {
        if (listIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Set<ContentType>> contentTypesByListId = new LinkedHashMap<>();
        for (UserListItemRepository.UserListContentType row : userListItemRepository.findDistinctContentTypesByUserListIdIn(listIds)) {
            contentTypesByListId.computeIfAbsent(row.getUserListId(), id -> new HashSet<>()).add(row.getType());
        }

        Map<UUID, UserListItemScope> scopeByListId = new LinkedHashMap<>();
        for (UUID listId : listIds) {
            boolean hasNestedLists = nestedListsCountByListId.getOrDefault(listId, 0L) > 0;
            Set<ContentType> types = contentTypesByListId.getOrDefault(listId, Set.of());
            UserListItemScope scope = UserListItemScope.resolve(types, hasNestedLists);
            if (scope != null) {
                scopeByListId.put(listId, scope);
            }
        }
        return scopeByListId;
    }

    @Override
    public Set<UUID> getListIdsContainingContent(Collection<UUID> listIds, UUID contentId) {
        if (listIds.isEmpty()) {
            return Set.of();
        }

        return userListItemRepository.findUserListIdsContainingContent(listIds, contentId);
    }

    @Override
    @Transactional
    public UserListItemResponseDTO addItem(UUID userId, UUID listId, UserListItemCreationDTO userListItemCreationDTO) {
        UserList userList = findOwnedListForUpdate(userId, listId);
        validateExactlyOneTarget(userListItemCreationDTO);

        LocalDateTime now = LocalDateTime.now();
        UserListItem.UserListItemBuilder builder = UserListItem.builder()
                .userList(userList)
                .description(userListItemCreationDTO.description())
                .createdAt(now)
                .updatedAt(now);

        if (userListItemCreationDTO.content() != null) {
            assertListIsNotLockedAsListOfLists(listId);
            assertContentTypeGroupMatches(resolveExistingContentScope(listId), userListItemCreationDTO.content().type());
            ContentRefDTO contentRef = contentService.getOrCreateReference(userListItemCreationDTO.content());
            builder.content(contentRepository.getReferenceById(contentRef.id()));
            builder.customPosterUrl(userListItemCreationDTO.customPosterUrl());
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
        UserList userList = findOwnedListForUpdate(userId, listId);
        assertListIsNotLockedAsListOfLists(listId);
        UserListItemScope lockedScope = resolveExistingContentScope(listId);

        int position = (int) userListItemRepository.countByUserListId(listId) + 1;
        LocalDateTime now = LocalDateTime.now();

        List<UserListItem> newItems = new ArrayList<>();
        for (ContentRefCreationDTO content : userListItemBulkCreationDTO.items()) {
            assertContentTypeGroupMatches(lockedScope, content.type());
            if (lockedScope == null) {
                lockedScope = UserListItemScope.forContentType(content.type());
            }
            ContentRefDTO contentRef = contentService.getOrCreateReference(content);
            newItems.add(UserListItem.builder()
                    .userList(userList)
                    .content(contentRepository.getReferenceById(contentRef.id()))
                    .position(position++)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }

        try {
            List<UserListItem> saved = userListItemRepository.saveAll(newItems);
            userListItemRepository.flush();
            return saved.stream().map(userListItemMapper::userListItemToResponseDto).toList();
        } catch (DataIntegrityViolationException e) {
            throw mapUniqueConstraintViolation(e);
        }
    }

    @Override
    @Transactional
    public UserListItemResponseDTO updateItem(UUID userId, UUID listId, UUID itemId, UserListItemPatchDTO userListItemPatchDTO) {
        findOwnedList(userId, listId);
        UserListItem item = findOwnedItem(listId, itemId);

        if (userListItemPatchDTO.customPosterUrl() != null && item.getChildList() != null) {
            throw new BadRequestException("customPosterUrl is only allowed on content items");
        }

        boolean descriptionChanged = userListItemPatchDTO.description() != null
                && !userListItemPatchDTO.description().equals(item.getDescription());
        boolean positionChanged = userListItemPatchDTO.position() != null
                && !userListItemPatchDTO.position().equals(item.getPosition());
        boolean customPosterUrlChanged = userListItemPatchDTO.customPosterUrl() != null
                && !userListItemPatchDTO.customPosterUrl().equals(item.getCustomPosterUrl());

        if (!descriptionChanged && !positionChanged && !customPosterUrlChanged) {
            return userListItemMapper.userListItemToResponseDto(item);
        }

        long currentCount = positionChanged ? userListItemRepository.countByUserListId(listId) : 0;
        if (positionChanged && userListItemPatchDTO.position() > currentCount) {
            throw new BadRequestException("position cannot be greater than " + currentCount + ", the last position in the list");
        }

        if (descriptionChanged) {
            item.setDescription(userListItemPatchDTO.description());
        }
        if (customPosterUrlChanged) {
            item.setCustomPosterUrl(userListItemPatchDTO.customPosterUrl());
        }
        item.setUpdatedAt(LocalDateTime.now());

        if (positionChanged) {
            try {
                item = performMove(item, item.getPosition(), userListItemPatchDTO.position(), currentCount);
            } catch (DataIntegrityViolationException e) {
                throw new ConflictException("List item could not be reordered due to a concurrent update");
            }
        } else {
            item = userListItemRepository.save(item);
            userListItemRepository.flush();
        }

        return userListItemMapper.userListItemToResponseDto(item);
    }

    private UserListItem performMove(UserListItem item, int oldPosition, int newPosition, long currentCount) {
        UUID listId = item.getUserList().getId();

        item.setPosition((int) currentCount + 1);
        userListItemRepository.save(item);
        userListItemRepository.flush();

        boolean movingForward = newPosition < oldPosition;
        int rangeStart = movingForward ? newPosition : oldPosition + 1;
        int rangeEnd = movingForward ? oldPosition - 1 : newPosition;
        int shiftDelta = movingForward ? 1 : -1;

        userListItemRepository.parkPositionsInRange(listId, rangeStart, rangeEnd, POSITION_PARK_OFFSET);
        userListItemRepository.settleParkedPositions(listId, POSITION_PARK_OFFSET, shiftDelta);

        item.setPosition(newPosition);
        UserListItem saved = userListItemRepository.save(item);
        userListItemRepository.flush();

        return saved;
    }

    @Override
    @Transactional
    public void removeItem(UUID userId, UUID listId, UUID itemId) {
        findOwnedList(userId, listId);

        UserListItem item = findOwnedItem(listId, itemId);

        deleteAndCloseGap(item);
    }

    @Override
    @Transactional
    public void removeItemsReferencingChildList(UUID childListId) {
        userListItemRepository.findByChildListId(childListId).forEach(this::deleteAndCloseGap);
    }

    private UserListItem findOwnedItem(UUID listId, UUID itemId) {
        UserListItem item = userListItemRepository.findById(itemId)
                .orElseThrow(() -> new NotFoundException("List item not found"));

        if (!item.getUserList().getId().equals(listId)) {
            throw new NotFoundException("List item not found");
        }

        return item;
    }

    private void validateExactlyOneTarget(UserListItemCreationDTO userListItemCreationDTO) {
        boolean hasContent = userListItemCreationDTO.content() != null;
        boolean hasChildList = userListItemCreationDTO.childListId() != null;

        if (hasContent == hasChildList) {
            throw new BadRequestException("Exactly one of content or childListId must be provided");
        }

        if (hasChildList && userListItemCreationDTO.customPosterUrl() != null) {
            throw new BadRequestException("customPosterUrl is only allowed on content items");
        }
    }

    private UserList resolveChildList(UUID userId, UUID parentListId, UUID childListId) {
        UserList childList = userListRepository.findById(childListId)
                .orElseThrow(() -> new NotFoundException("List not found"));

        if (childListId.equals(parentListId)) {
            throw new BadRequestException("A list cannot reference itself");
        }

        assertListIsVisibleTo(userId, childList);

        if (userListItemRepository.existsByChildListId(parentListId)) {
            throw new BadRequestException("This list is already nested inside another list; nesting depth is limited to one level");
        }

        if (userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(childListId)) {
            throw new BadRequestException("The referenced list already contains nested lists; nesting depth is limited to one level");
        }

        return childList;
    }

    private void assertListIsVisibleTo(UUID viewerId, UserList list) {
        if (!isVisibleTo(viewerId, list)) {
            throw new ForbiddenException("This list is private");
        }
    }

    private boolean isVisibleTo(UUID viewerId, UserList list) {
        UUID ownerId = list.getUser().getId();

        if (viewerId.equals(ownerId) || list.getVisibility() == UserListVisibility.PUBLIC) {
            return true;
        }

        return list.getVisibility() == UserListVisibility.FOLLOWERS
                && followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, ownerId, FollowStatus.ACCEPTED);
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
        long currentCount = userListItemRepository.countByUserListId(listId);
        int targetPosition = requestedPosition != null ? requestedPosition : (int) currentCount + 1;

        if (targetPosition > currentCount + 1) {
            throw new BadRequestException("position cannot be greater than " + (currentCount + 1) + ", the next free position in the list");
        }

        if (targetPosition <= currentCount) {
            userListItemRepository.parkPositionsInRange(listId, targetPosition, Integer.MAX_VALUE, POSITION_PARK_OFFSET);
            userListItemRepository.settleParkedPositions(listId, POSITION_PARK_OFFSET, 1);
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

        userListItemRepository.parkPositionsInRange(listId, removedPosition + 1, Integer.MAX_VALUE, POSITION_PARK_OFFSET);
        userListItemRepository.settleParkedPositions(listId, POSITION_PARK_OFFSET, -1);
    }

    private UserList findOwnedList(UUID userId, UUID listId) {
        UserList userList = userListRepository.findById(listId)
                .orElseThrow(() -> new NotFoundException("List not found"));

        if (!userList.getUser().getId().equals(userId)) {
            throw new NotFoundException("List not found");
        }

        return userList;
    }

    private UserList findOwnedListForUpdate(UUID userId, UUID listId) {
        UserList userList = userListRepository.findByIdForUpdate(listId)
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

        log.warn("Unmapped data integrity violation while inserting a user list item (constraint={})", constraintName, e);
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