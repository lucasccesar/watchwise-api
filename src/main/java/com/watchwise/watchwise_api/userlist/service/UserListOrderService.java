package com.watchwise.watchwise_api.userlist.service;

import com.watchwise.watchwise_api.userlist.dto.UserListOrderPageDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListOrderPatchDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListOrderRevisionDTO;

import java.util.UUID;

public interface UserListOrderService {

    UserListOrderPageDTO getOwnedListOrder(UUID userId, String cursor, Integer size);

    UserListOrderPageDTO getListItemOrder(UUID userId, UUID listId, String cursor, Integer size);

    UserListOrderRevisionDTO reorderOwnedLists(UUID userId, UserListOrderPatchDTO patch);

    UserListOrderRevisionDTO reorderListItems(UUID userId, UUID listId, UserListOrderPatchDTO patch);
}
