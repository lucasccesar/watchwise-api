package com.watchwise.watchwise_api.userlist.service;

import com.watchwise.watchwise_api.userlist.dto.UserListCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListResponseDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface UserListService {

    Page<UserListResponseDTO> getUserLists(UUID viewerId, UUID userId, Integer pageNumber, Integer pageSize);

    UserListResponseDTO createUserList(UUID userId, UserListCreationDTO userListCreationDTO);

    UserListResponseDTO updateUserList(UUID userId, UUID listId, UserListCreationDTO userListCreationDTO);

    void deleteUserList(UUID userId, UUID listId);

}