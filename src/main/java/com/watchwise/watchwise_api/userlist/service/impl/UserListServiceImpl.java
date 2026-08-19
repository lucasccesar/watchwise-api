package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.dto.UserListCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListResponseDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.mapper.UserListMapper;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.service.UserListService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserListServiceImpl implements UserListService {

    private final UserListRepository userListRepository;
    private final UserRepository userRepository;
    private final UserListMapper userListMapper;

    static final int DEFAULT_PAGE = 0;
    static final int DEFAULT_PAGE_SIZE = 20;

    @Override
    public Page<UserListResponseDTO> getUserLists(UUID viewerId, UUID userId, Integer pageNumber, Integer pageSize) {
        userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        PageRequest pageRequest = buildPageRequest(pageNumber, pageSize);

        Page<UserList> lists = viewerId.equals(userId)
                ? userListRepository.findByUserId(userId, pageRequest)
                : userListRepository.findByUserIdAndIsPublicTrue(userId, pageRequest);

        return lists.map(userListMapper::userListToResponseDto);
    }

    @Override
    @Transactional
    public UserListResponseDTO createUserList(UUID userId, UserListCreationDTO userListCreationDTO) {
        User user = userRepository.getReferenceById(userId);
        LocalDateTime now = LocalDateTime.now();

        UserList userList = UserList.builder()
                .user(user)
                .name(userListCreationDTO.name())
                .description(userListCreationDTO.description())
                .isPublic(userListCreationDTO.isPublic() != null ? userListCreationDTO.isPublic() : true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return userListMapper.userListToResponseDto(userListRepository.save(userList));
    }

    @Override
    @Transactional
    public UserListResponseDTO updateUserList(UUID userId, UUID listId, UserListCreationDTO userListCreationDTO) {
        UserList userList = findOwnedList(userId, listId);

        userList.setName(userListCreationDTO.name());
        userList.setDescription(userListCreationDTO.description());
        userList.setIsPublic(userListCreationDTO.isPublic() != null ? userListCreationDTO.isPublic() : true);
        userList.setUpdatedAt(LocalDateTime.now());

        return userListMapper.userListToResponseDto(userListRepository.save(userList));
    }

    @Override
    @Transactional
    public void deleteUserList(UUID userId, UUID listId) {
        UserList userList = findOwnedList(userId, listId);

        userListRepository.delete(userList);
    }

    private UserList findOwnedList(UUID userId, UUID listId) {
        UserList userList = userListRepository.findById(listId)
                .orElseThrow(() -> new NotFoundException("List not found"));

        if (!userList.getUser().getId().equals(userId)) {
            throw new NotFoundException("List not found");
        }

        return userList;
    }

    public PageRequest buildPageRequest(Integer pageNumber, Integer pageSize) {
        int queryPageNumber;
        int queryPageSize;

        if (pageNumber != null && pageNumber > 0) {
            queryPageNumber = pageNumber - 1;
        } else if (pageNumber == null || pageNumber == 0) {
            queryPageNumber = DEFAULT_PAGE;
        } else {
            throw new BadRequestException("Page number must be greater than or equal to 0");
        }

        if (pageSize == null || pageSize > 1000) {
            queryPageSize = DEFAULT_PAGE_SIZE;
        } else {
            if (pageSize <= 0) {
                throw new BadRequestException("Page size must be greater than 0");
            } else {
                queryPageSize = pageSize;
            }
        }

        return PageRequest.of(queryPageNumber, queryPageSize);
    }
}