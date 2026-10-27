package com.watchwise.watchwise_api.userlist.service;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPatchDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;
import com.watchwise.watchwise_api.userlist.dto.UserListScopeSummary;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.watchwise.watchwise_api.userlist.service.ImportedUserListItemResult;

public interface UserListItemService {

    List<UserListItemResponseDTO> getItems(UUID viewerId, UUID listId);

    UserListItemsWithState getItemsWithState(UUID viewerId, UUID listId);

    List<ContentRefDTO> getPreviewItems(UUID listId);

    long countNestedLists(UUID listId);

    double getWatchedPercentage(UUID listId, UUID ownerId);

    Map<UUID, List<ContentRefDTO>> getPreviewItemsByListIds(Collection<UUID> listIds);

    Map<UUID, Long> countNestedListsByListIds(Collection<UUID> listIds);

    Map<UUID, Double> getWatchedPercentagesByListIds(Collection<UUID> listIds, UUID ownerId);

    long getItemsCount(UUID listId);

    Map<UUID, Long> getItemsCountByListIds(Collection<UUID> listIds);

    long getTotalRuntimeMinutes(UUID listId);

    Map<UUID, Long> getTotalRuntimeMinutesByListIds(Collection<UUID> listIds);

    UserListItemScope getItemScope(UUID listId);

    Map<UUID, UserListItemScope> getItemScopeByListIds(Collection<UUID> listIds);

    Map<UUID, UserListItemScope> getItemScopeByListIds(Collection<UUID> listIds, Map<UUID, Long> nestedListsCountByListId);

    Map<UUID, UserListScopeSummary> getScopeSummariesByListIds(
            Collection<UUID> listIds, Map<UUID, Long> nestedListsCountByListId);

    Set<UUID> getListIdsContainingContent(Collection<UUID> listIds, UUID contentId);

    UserListItemResponseDTO addItem(UUID userId, UUID listId, UserListItemCreationDTO userListItemCreationDTO);

    List<UserListItemResponseDTO> addItems(UUID userId, UUID listId, UserListItemBulkCreationDTO userListItemBulkCreationDTO);

    UserListItemsWithState addItemsWithState(
            UUID userId, UUID listId, UserListItemBulkCreationDTO userListItemBulkCreationDTO);

    UserListItemsWithState addItemsWithState(
            UUID userId, UUID listId, UserListItemBulkCreationDTO userListItemBulkCreationDTO,
            List<ContentRefDTO> resolvedContentRefs);

    List<ContentRefDTO> resolveBulkContentReferences(List<ContentRefCreationDTO> items);

    int nextImportedPosition(UUID userId, UUID listId);

    ImportedUserListBatchResult appendImportedItems(
            UUID userId, UUID listId, List<UUID> contentIds, Map<UUID, String> descriptionsByContentId,
            int nextPosition);

    UserListItemResponseDTO updateItem(UUID userId, UUID listId, UUID itemId, UserListItemPatchDTO userListItemPatchDTO);

    void removeItem(UUID userId, UUID listId, UUID itemId);

    void removeItemsReferencingChildList(UUID childListId);

    void removeItemsReferencingChildLists(Collection<UUID> childListIds);

}
