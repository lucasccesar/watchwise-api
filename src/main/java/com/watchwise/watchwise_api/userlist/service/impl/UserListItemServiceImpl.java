package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.common.transaction.UserListStructureLock;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.dto.ContentStateDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.userlist.dto.UserListItemBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPatchDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;
import com.watchwise.watchwise_api.userlist.dto.UserListDisplayScope;
import com.watchwise.watchwise_api.userlist.dto.UserListScopeSummary;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.mapper.UserListItemMapper;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import com.watchwise.watchwise_api.userlist.service.UserListContentStateResult;
import com.watchwise.watchwise_api.userlist.service.UserListContentStateService;
import com.watchwise.watchwise_api.userlist.service.UserListItemsWithState;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    private final UserListContentStateService userListContentStateService;
    private final UserContentPosterService userContentPosterService;
    private final UserBlockAccessService userBlockAccessService;
    private final ImportLedger importLedger;
    private final UserRepository userRepository;
    private final AdvisoryLock advisoryLock;
    private final EntityManager entityManager;

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
        return getItemsWithState(viewerId, listId).items();
    }

    @Override
    public UserListItemsWithState getItemsWithState(UUID viewerId, UUID listId) {
        List<UserListItem> entities = userListItemRepository
                .findByUserListIdWithContentAndChildListOrderByPositionAsc(listId);
        UserListContentStateResult stateResult = userListContentStateService.resolve(viewerId, entities);
        Map<UUID, String> customPosterByContentId = loadPostersForOwner(entities);
        Set<UUID> visibleChildListIds = loadVisibleChildListIds(viewerId, entities);
        List<UserListItemResponseDTO> items = entities.stream()
                .map(item -> toVisibilityScopedResponseDto(
                        item, stateResult.stateByItemId(), customPosterByContentId, visibleChildListIds))
                .toList();
        return new UserListItemsWithState(
                items,
                stateResult.watchedPercentageByListId().getOrDefault(listId, 0.0));
    }

    private UserListItemResponseDTO toVisibilityScopedResponseDto(
            UserListItem item, Map<UUID, ContentStateDTO> stateByItemId,
            Map<UUID, String> customPosterByContentId, Set<UUID> visibleChildListIds) {
        UserListItemResponseDTO dto = userListItemMapper.userListItemToResponseDto(item);
        ContentStateDTO contentState = item.getId() == null ? null : stateByItemId.get(item.getId());
        String customPosterUrl = item.getContent() == null
                ? null
                : customPosterByContentId.get(item.getContent().getId());

        if (item.getChildList() != null && !visibleChildListIds.contains(item.getChildList().getId())) {
            return new UserListItemResponseDTO(
                    dto.id(), dto.content(), null, dto.position(), dto.description(), dto.createdAt(), dto.updatedAt(),
                    null, null);
        }

        return new UserListItemResponseDTO(
                dto.id(), dto.content(), dto.childList(), dto.position(), dto.description(), dto.createdAt(), dto.updatedAt(),
                customPosterUrl, contentState);
    }

    private Set<UUID> loadVisibleChildListIds(UUID viewerId, List<UserListItem> items) {
        List<UUID> childListIds = items.stream()
                .map(UserListItem::getChildList)
                .filter(java.util.Objects::nonNull)
                .map(UserList::getId)
                .distinct()
                .toList();
        if (childListIds.isEmpty()) {
            return Set.of();
        }
        return userListItemRepository.findVisibleChildListsByIdIn(childListIds, viewerId).stream()
                .map(UserList::getId)
                .collect(Collectors.toSet());
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
    public double getWatchedPercentage(UUID listId, UUID viewerId) {
        return getItemsWithState(viewerId, listId).watchedPercentage();
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
    public Map<UUID, Double> getWatchedPercentagesByListIds(Collection<UUID> listIds, UUID viewerId) {
        if (listIds.isEmpty()) {
            return Map.of();
        }

        List<UserListItem> allItems = userListItemRepository
                .findAllContentItemsByUserListIdInOrderByPosition(listIds);
        return userListContentStateService.resolve(viewerId, allItems).watchedPercentageByListId();
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
        return getScopeSummariesByListIds(listIds, nestedListsCountByListId).entrySet().stream()
                .filter(entry -> entry.getValue().itemScope() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().itemScope(),
                        (left, right) -> left, LinkedHashMap::new));
    }

    @Override
    public Map<UUID, UserListScopeSummary> getScopeSummariesByListIds(
            Collection<UUID> listIds, Map<UUID, Long> nestedListsCountByListId) {
        if (listIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Set<ContentType>> contentTypesByListId = new LinkedHashMap<>();
        for (UserListItemRepository.UserListContentType row : userListItemRepository.findDistinctContentTypesByUserListIdIn(listIds)) {
            contentTypesByListId.computeIfAbsent(row.getUserListId(), id -> new HashSet<>()).add(row.getType());
        }

        Map<UUID, UserListScopeSummary> scopeByListId = new LinkedHashMap<>();
        for (UUID listId : listIds) {
            boolean hasNestedLists = nestedListsCountByListId.getOrDefault(listId, 0L) > 0;
            Set<ContentType> types = contentTypesByListId.getOrDefault(listId, Set.of());
            UserListItemScope scope = UserListItemScope.resolve(types, hasNestedLists);
            scopeByListId.put(listId, new UserListScopeSummary(
                    scope, UserListDisplayScope.resolve(types, hasNestedLists)));
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
        validateExactlyOneTarget(userListItemCreationDTO);

        findOwnedList(userId, listId);

        if (userListItemCreationDTO.content() != null) {
            assertListIsNotLockedAsListOfLists(listId);
            assertContentTypeGroupMatches(
                    resolveExistingContentScope(listId), userListItemCreationDTO.content().type());
        } else {
            assertListIsNotLockedAsContentList(listId);
        }

        ContentRefDTO resolvedContent = userListItemCreationDTO.content() == null
                ? null
                : contentService.getOrCreateReference(userListItemCreationDTO.content());

        UserList userList = userListItemCreationDTO.childListId() == null
                ? findOwnedListForUpdate(userId, listId)
                : lockParentAndChildForStructure(userId, listId, userListItemCreationDTO.childListId());
        lockListItemRows(listId);

        LocalDateTime now = LocalDateTime.now();
        UserListItem.UserListItemBuilder builder = UserListItem.builder()
                .userList(userList)
                .description(userListItemCreationDTO.description())
                .createdAt(now)
                .updatedAt(now);

        if (userListItemCreationDTO.content() != null) {
            assertListIsNotLockedAsListOfLists(listId);
            assertContentTypeGroupMatches(resolveExistingContentScope(listId), userListItemCreationDTO.content().type());
            builder.content(contentRepository.getReferenceById(resolvedContent.id()));
        } else {
            assertListIsNotLockedAsContentList(listId);
            builder.childList(resolveChildList(userId, listId, userListItemCreationDTO.childListId()));
        }

        UserListItem newItem = builder.build();

        try {
            UserListItem saved = insertAtPosition(listId, newItem, userListItemCreationDTO.position());
            if (userListItemCreationDTO.customPosterUrl() != null) {
                userContentPosterService.upsert(
                        userId, saved.getContent().getId(), userListItemCreationDTO.customPosterUrl());
            }
            return toResponseDto(userId, saved);
        } catch (DataIntegrityViolationException e) {
            throw mapUniqueConstraintViolation(e);
        }
    }

    @Override
    @Transactional
    public List<UserListItemResponseDTO> addItems(UUID userId, UUID listId, UserListItemBulkCreationDTO userListItemBulkCreationDTO) {
        return addItemsWithState(userId, listId, userListItemBulkCreationDTO).items();
    }

    @Override
    @Transactional
    public UserListItemsWithState addItemsWithState(
            UUID userId, UUID listId, UserListItemBulkCreationDTO userListItemBulkCreationDTO) {
        findOwnedList(userId, listId);
        assertListIsNotLockedAsListOfLists(listId);
        UserListItemScope preflightScope = resolveExistingContentScope(listId);
        List<ContentRefDTO> resolvedContentRefs = resolveBulkContentReferences(
                userListItemBulkCreationDTO.items(), preflightScope);
        return addResolvedItemsWithState(userId, listId, userListItemBulkCreationDTO,
                resolvedContentRefs, preflightScope);
    }

    @Override
    @Transactional
    public UserListItemsWithState addItemsWithState(
            UUID userId, UUID listId, UserListItemBulkCreationDTO userListItemBulkCreationDTO,
            List<ContentRefDTO> resolvedContentRefs) {
        findOwnedList(userId, listId);
        assertListIsNotLockedAsListOfLists(listId);
        UserListItemScope preflightScope = resolveExistingContentScope(listId);
        return addResolvedItemsWithState(userId, listId, userListItemBulkCreationDTO,
                resolvedContentRefs, preflightScope);
    }

    @Override
    @Transactional
    public List<ContentRefDTO> resolveBulkContentReferences(List<ContentRefCreationDTO> items) {
        return resolveBulkContentReferences(items, null);
    }

    private List<ContentRefDTO> resolveBulkContentReferences(
            List<ContentRefCreationDTO> items, UserListItemScope initialScope) {
        List<ContentRefDTO> resolvedContentRefs = new ArrayList<>(items.size());
        UserListItemScope scope = initialScope;
        for (ContentRefCreationDTO content : items) {
            assertContentTypeGroupMatches(scope, content.type());
            if (scope == null) {
                scope = UserListItemScope.forContentType(content.type());
            }
            resolvedContentRefs.add(contentService.getOrCreateReference(content));
        }
        return List.copyOf(resolvedContentRefs);
    }

    private UserListItemsWithState addResolvedItemsWithState(
            UUID userId, UUID listId, UserListItemBulkCreationDTO userListItemBulkCreationDTO,
            List<ContentRefDTO> resolvedContentRefs, UserListItemScope preflightScope) {
        if (resolvedContentRefs == null || resolvedContentRefs.size() != userListItemBulkCreationDTO.items().size()) {
            throw new BadRequestException("Content references could not be resolved for all list items");
        }
        UserListItemScope resolvedScope = preflightScope;
        for (int index = 0; index < resolvedContentRefs.size(); index++) {
            ContentRefCreationDTO requested = userListItemBulkCreationDTO.items().get(index);
            assertContentTypeGroupMatches(resolvedScope, requested.type());
            if (resolvedScope == null) {
                resolvedScope = UserListItemScope.forContentType(requested.type());
            }
            if (resolvedContentRefs.get(index).type() != requested.type()) {
                throw new BadRequestException("Resolved content type does not match requested content type");
            }
        }

        UserList userList = findOwnedListForUpdate(userId, listId);
        lockListItemRows(listId);
        assertListIsNotLockedAsListOfLists(listId);
        UserListItemScope lockedScope = resolveExistingContentScope(listId);

        int position = (int) userListItemRepository.countByUserListId(listId) + 1;
        LocalDateTime now = LocalDateTime.now();

        List<UserListItem> newItems = new ArrayList<>();
        for (int index = 0; index < userListItemBulkCreationDTO.items().size(); index++) {
            ContentRefCreationDTO content = userListItemBulkCreationDTO.items().get(index);
            assertContentTypeGroupMatches(lockedScope, content.type());
            if (lockedScope == null) {
                lockedScope = UserListItemScope.forContentType(content.type());
            }
            ContentRefDTO contentRef = resolvedContentRefs.get(index);
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
            UserListContentStateResult stateResult = userListContentStateService.resolve(userId, saved);
            Map<UUID, String> customPosterByContentId = loadPostersForOwner(saved);
            List<UserListItemResponseDTO> items = saved.stream()
                    .map(item -> toVisibilityScopedResponseDto(
                            item, stateResult.stateByItemId(), customPosterByContentId, Set.of()))
                    .toList();
            return new UserListItemsWithState(
                    items,
                    stateResult.watchedPercentageByListId().getOrDefault(listId, 0.0));
        } catch (DataIntegrityViolationException e) {
            throw mapUniqueConstraintViolation(e);
        }
    }

    @Override
    @Transactional
    public int nextImportedPosition(UUID userId, UUID listId) {
        findOwnedListForUpdate(userId, listId);
        assertListIsNotLockedAsListOfLists(listId);
        return Math.addExact(userListItemRepository.findMaxPositionByUserListId(listId), 1);
    }

    @Override
    @Transactional
    public com.watchwise.watchwise_api.userlist.service.ImportedUserListBatchResult appendImportedItems(
            UUID userId, UUID listId, List<UUID> contentIds, Map<UUID, String> descriptionsByContentId,
            int nextPosition) {
        if (contentIds == null || contentIds.isEmpty()) {
            return new com.watchwise.watchwise_api.userlist.service.ImportedUserListBatchResult(List.of(), nextPosition);
        }
        if (nextPosition < 1) throw new IllegalArgumentException("Next user list position must be positive");
        if (descriptionsByContentId != null && descriptionsByContentId.values().stream()
                .filter(java.util.Objects::nonNull).anyMatch(description -> description.length() > 400)) {
            throw new BadRequestException("Imported list item description cannot exceed 400 characters");
        }
        UserList userList = findOwnedListForUpdate(userId, listId);
        lockListItemRows(listId);
        assertListIsNotLockedAsListOfLists(listId);

        List<UUID> distinctIds = contentIds.stream().distinct().toList();
        Map<UUID, Content> contentById = contentRepository.findAllById(distinctIds).stream()
                .collect(Collectors.toMap(Content::getId, content -> content));
        if (contentById.size() != distinctIds.size()) {
            throw new NotFoundException("Imported list content reference not found");
        }

        UserListItemScope lockedScope = resolveExistingContentScope(listId);
        for (UUID contentId : distinctIds) {
            Content content = contentById.get(contentId);
            assertContentTypeGroupMatches(lockedScope, content.getType());
            if (lockedScope == null) lockedScope = UserListItemScope.forContentType(content.getType());
        }

        Map<UUID, UserListItem> existingByContentId = userListItemRepository
                .findByUserListIdAndContentIdInWithContent(listId, distinctIds).stream()
                .collect(Collectors.toMap(item -> item.getContent().getId(), item -> item));
        LocalDateTime now = LocalDateTime.now();
        Map<UUID, UserListItem> pendingByContentId = new LinkedHashMap<>();
        int nextLockedPosition = Math.addExact(userListItemRepository.findMaxPositionByUserListId(listId), 1);
        int positionCursor = nextLockedPosition;
        for (UUID contentId : distinctIds) {
            if (existingByContentId.containsKey(contentId)) continue;
            pendingByContentId.put(contentId, UserListItem.builder()
                    .userList(userList)
                    .content(contentRepository.getReferenceById(contentId))
                    .position(positionCursor)
                    .description(descriptionsByContentId == null ? null : descriptionsByContentId.get(contentId))
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            positionCursor = Math.addExact(positionCursor, 1);
        }

        try {
            List<UserListItem> saved = userListItemRepository.saveAll(pendingByContentId.values());
            userListItemRepository.flush();
            Map<UUID, UserListItem> savedByContentId = saved.stream()
                    .collect(Collectors.toMap(item -> item.getContent().getId(), item -> item));
            List<com.watchwise.watchwise_api.userlist.service.ImportedUserListItemResult> results = new ArrayList<>();
            for (UUID contentId : contentIds) {
                UserListItem existing = existingByContentId.get(contentId);
                if (existing != null) {
                    results.add(new com.watchwise.watchwise_api.userlist.service.ImportedUserListItemResult(
                            contentId, existing.getId(), false));
                    continue;
                }
                UserListItem inserted = savedByContentId.get(contentId);
                results.add(new com.watchwise.watchwise_api.userlist.service.ImportedUserListItemResult(
                        contentId, inserted.getId(), true));
            }
            int insertedCount = Math.toIntExact(saved.size());
            return new com.watchwise.watchwise_api.userlist.service.ImportedUserListBatchResult(
                    results, Math.addExact(nextLockedPosition, insertedCount));
        } catch (DataIntegrityViolationException exception) {
            throw mapUniqueConstraintViolation(exception);
        }
    }

    @Override
    @Transactional
    public UserListItemResponseDTO updateItem(UUID userId, UUID listId, UUID itemId, UserListItemPatchDTO userListItemPatchDTO) {
        findOwnedListForUpdate(userId, listId);
        lockListItemRows(listId);
        UserListItem item = findOwnedItem(listId, itemId);

        if (userListItemPatchDTO.customPosterUrl() != null && item.getChildList() != null) {
            throw new BadRequestException("customPosterUrl is only allowed on content items");
        }

        boolean descriptionChanged = userListItemPatchDTO.description() != null
                && !userListItemPatchDTO.description().equals(item.getDescription());
        boolean positionChanged = userListItemPatchDTO.position() != null
                && !userListItemPatchDTO.position().equals(item.getPosition());
        boolean customPosterUrlProvided = userListItemPatchDTO.customPosterUrl() != null;

        if (!descriptionChanged && !positionChanged && !customPosterUrlProvided) {
            return toResponseDto(userId, item);
        }

        long currentCount = positionChanged ? userListItemRepository.countByUserListId(listId) : 0;
        if (positionChanged && userListItemPatchDTO.position() > currentCount) {
            throw new BadRequestException("position cannot be greater than " + currentCount + ", the last position in the list");
        }

        if (descriptionChanged) {
            item.setDescription(userListItemPatchDTO.description());
        }
        if (descriptionChanged || positionChanged) {
            item.setUpdatedAt(LocalDateTime.now());
        }

        if (positionChanged) {
            try {
                item = performMove(item, item.getPosition(), userListItemPatchDTO.position(), currentCount);
            } catch (DataIntegrityViolationException e) {
                throw new ConflictException("List item could not be reordered due to a concurrent update");
            }
        } else {
            if (descriptionChanged) {
                item = userListItemRepository.save(item);
                userListItemRepository.flush();
            }
        }

        if (customPosterUrlProvided) {
            userContentPosterService.upsert(
                    userId, item.getContent().getId(), userListItemPatchDTO.customPosterUrl());
        }

        return toResponseDto(userId, item);
    }

    private UserListItemResponseDTO toResponseDto(UUID viewerId, UserListItem item) {
        UserListContentStateResult stateResult = userListContentStateService.resolve(viewerId, List.of(item));
        Map<UUID, String> customPosterByContentId = loadPostersForOwner(List.of(item));
        Set<UUID> visibleChildListIds = loadVisibleChildListIds(viewerId, List.of(item));
        return toVisibilityScopedResponseDto(
                item, stateResult.stateByItemId(), customPosterByContentId, visibleChildListIds);
    }

    private Map<UUID, String> loadPostersForOwner(List<UserListItem> items) {
        if (items.isEmpty()) {
            return Map.of();
        }

        UUID ownerId = items.get(0).getUserList().getUser().getId();
        List<UUID> contentIds = items.stream()
                .map(UserListItem::getContent)
                .filter(java.util.Objects::nonNull)
                .map(content -> content.getId())
                .distinct()
                .toList();
        return contentIds.isEmpty()
                ? Map.of()
                : userContentPosterService.findByUserAndContentIds(ownerId, contentIds);
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
        UserListItem preflightItem = userListItemRepository.findById(itemId).orElse(null);
        boolean removesChildEdge = preflightItem != null
                && preflightItem.getUserList().getId().equals(listId)
                && preflightItem.getChildList() != null;
        if (removesChildEdge) {
            lockParentAndChildForStructure(userId, listId, preflightItem.getChildList().getId());
        } else {
            findOwnedListForUpdate(userId, listId);
        }
        lockListItemRows(listId);

        UserListItem item = findOwnedItem(listId, itemId);

        deleteAndCloseGap(item);
    }

    @Override
    @Transactional
    public void removeItemsReferencingChildList(UUID childListId) {
        removeItemsReferencingChildLists(List.of(childListId));
    }

    @Override
    @Transactional
    public void removeItemsReferencingChildLists(Collection<UUID> childListIds) {
        if (childListIds == null || childListIds.isEmpty()) {
            return;
        }

        userListItemRepository.findByChildListIdInForUpdateOrderByParentAndPositionDesc(childListIds)
                .forEach(this::deleteAndCloseGap);
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

        if (viewerId.equals(ownerId)) {
            return true;
        }
        if (userBlockAccessService.isBlockedEitherDirection(viewerId, ownerId)) {
            return false;
        }
        if (list.getVisibility() == UserListVisibility.PUBLIC) {
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
        importLedger.markDeletedByTarget(item.getUserList().getUser().getId(), "USER_LIST_ITEM", item.getId());
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
        lockOwners(List.of(userId));
        UserList userList = userListRepository.findByIdForUpdate(listId)
                .orElseThrow(() -> new NotFoundException("List not found"));

        if (!userList.getUser().getId().equals(userId)) {
            throw new NotFoundException("List not found");
        }

        return userList;
    }

    private UserList lockParentAndChildForStructure(UUID userId, UUID parentListId, UUID childListId) {
        advisoryLock.lock(UserListStructureLock.IDENTITY);

        UserList parentSnapshot = userListRepository.findById(parentListId)
                .orElseThrow(() -> new NotFoundException("List not found"));
        if (!parentSnapshot.getUser().getId().equals(userId)) {
            throw new NotFoundException("List not found");
        }
        UserList childSnapshot = userListRepository.findById(childListId)
                .orElseThrow(() -> new NotFoundException("List not found"));

        lockOwners(List.of(parentSnapshot.getUser().getId(), childSnapshot.getUser().getId()));

        TreeSet<UUID> listIds = new TreeSet<>();
        listIds.add(parentListId);
        listIds.add(childListId);
        Map<UUID, UserList> lockedLists = new HashMap<>();
        for (UUID id : listIds) {
            UserList lockedList = userListRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new NotFoundException("List not found"));
            entityManager.refresh(lockedList);
            lockedLists.put(id, lockedList);
        }

        UserList parent = lockedLists.get(parentListId);
        UserList child = lockedLists.get(childListId);
        if (!parent.getUser().getId().equals(userId)
                || !child.getUser().getId().equals(childSnapshot.getUser().getId())) {
            throw new NotFoundException("List not found");
        }

        for (UUID id : listIds) {
            lockListItemRows(id);
        }
        return parent;
    }

    private void lockOwners(Collection<UUID> ownerIds) {
        new TreeSet<>(ownerIds).forEach(ownerId -> userRepository.findByIdForListCreation(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found")));
    }

    private void lockListItemRows(UUID listId) {
        userListItemRepository.findByUserListIdForUpdateOrderByPositionAsc(listId);
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
