package com.watchwise.watchwise_api.userlist.service;

import com.watchwise.watchwise_api.userlist.dto.UserListItemCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;

import java.util.UUID;

public interface UserListItemService {

    UserListItemResponseDTO addItem(UUID userId, UUID listId, UserListItemCreationDTO userListItemCreationDTO);

    void removeItem(UUID userId, UUID listId, UUID itemId);

}