package com.watchwise.watchwise_api.userlist.service;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface UserListItemService {

    List<UserListItemResponseDTO> getItems(UUID listId);

    List<ContentRefDTO> getPreviewItems(UUID listId);

    long countNestedLists(UUID listId);

    double getWatchedPercentage(UUID listId, UUID ownerId);

    Map<UUID, List<ContentRefDTO>> getPreviewItemsByListIds(Collection<UUID> listIds);

    Map<UUID, Long> countNestedListsByListIds(Collection<UUID> listIds);

    Map<UUID, Double> getWatchedPercentagesByListIds(Collection<UUID> listIds, UUID ownerId);

    UserListItemResponseDTO addItem(UUID userId, UUID listId, UserListItemCreationDTO userListItemCreationDTO);

    List<UserListItemResponseDTO> addItems(UUID userId, UUID listId, UserListItemBulkCreationDTO userListItemBulkCreationDTO);

    void removeItem(UUID userId, UUID listId, UUID itemId);

}