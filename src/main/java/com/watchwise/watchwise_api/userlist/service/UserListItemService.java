package com.watchwise.watchwise_api.userlist.service;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPatchDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface UserListItemService {

    List<UserListItemResponseDTO> getItems(UUID viewerId, UUID listId);

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

    Set<UUID> getListIdsContainingContent(Collection<UUID> listIds, UUID contentId);

    UserListItemResponseDTO addItem(UUID userId, UUID listId, UserListItemCreationDTO userListItemCreationDTO);

    List<UserListItemResponseDTO> addItems(UUID userId, UUID listId, UserListItemBulkCreationDTO userListItemBulkCreationDTO);

    UserListItemResponseDTO updateItem(UUID userId, UUID listId, UUID itemId, UserListItemPatchDTO userListItemPatchDTO);

    void removeItem(UUID userId, UUID listId, UUID itemId);

    void removeItemsReferencingChildList(UUID childListId);

}