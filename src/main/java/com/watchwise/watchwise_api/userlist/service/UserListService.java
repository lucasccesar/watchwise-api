package com.watchwise.watchwise_api.userlist.service;

import com.watchwise.watchwise_api.userlist.dto.UserListBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListDetailedResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListPatchDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListResponseDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface UserListService {

    Page<UserListResponseDTO> getUserLists(UUID viewerId, UUID userId, Integer pageNumber, Integer pageSize);

    UserListDetailedResponseDTO getUserListById(UUID viewerId, UUID listId);

    UserListResponseDTO createUserList(UUID userId, UserListCreationDTO userListCreationDTO);

    UserListDetailedResponseDTO createUserListWithItems(UUID userId, UserListBulkCreationDTO userListBulkCreationDTO);

    UserListResponseDTO updateUserList(UUID userId, UUID listId, UserListPatchDTO userListPatchDTO);

    void deleteUserList(UUID userId, UUID listId);

}