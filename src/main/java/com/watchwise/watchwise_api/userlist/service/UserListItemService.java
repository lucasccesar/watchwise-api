package com.watchwise.watchwise_api.userlist.service;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;

import java.util.List;
import java.util.UUID;

public interface UserListItemService {

    List<UserListItemResponseDTO> getItems(UUID listId);

    List<ContentRefDTO> getPreviewItems(UUID listId);

    long countNestedLists(UUID listId);

    UserListItemResponseDTO addItem(UUID userId, UUID listId, UserListItemCreationDTO userListItemCreationDTO);

    List<UserListItemResponseDTO> addItems(UUID userId, UUID listId, UserListItemBulkCreationDTO userListItemBulkCreationDTO);

    void removeItem(UUID userId, UUID listId, UUID itemId);

}