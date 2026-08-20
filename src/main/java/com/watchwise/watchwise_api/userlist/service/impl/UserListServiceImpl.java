package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.dto.UserListBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListDetailedResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListPatchDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListResponseDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.mapper.UserListMapper;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import com.watchwise.watchwise_api.userlist.service.UserListService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserListServiceImpl implements UserListService {

    private final UserListRepository userListRepository;
    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final UserListItemService userListItemService;
    private final UserListMapper userListMapper;

    static final int DEFAULT_PAGE = 0;
    static final int DEFAULT_PAGE_SIZE = 20;

    @Override
    public Page<UserListResponseDTO> getUserLists(UUID viewerId, UUID userId, Integer pageNumber, Integer pageSize) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        boolean isOwner = viewerId.equals(userId);
        boolean viewerFollowsTarget = !isOwner && viewerFollowsTarget(viewerId, userId);

        assertCanViewLists(target, isOwner, viewerFollowsTarget);

        PageRequest pageRequest = buildPageRequest(pageNumber, pageSize);

        Page<UserList> lists = isOwner
                ? userListRepository.findByUserId(userId, pageRequest)
                : userListRepository.findByUserIdAndVisibilityIn(userId, visibleVisibilitiesFor(viewerFollowsTarget), pageRequest);

        return lists.map(list -> toResponseDto(list, viewerId));
    }

    private UserListResponseDTO toResponseDto(UserList userList, UUID viewerId) {
        List<ContentRefDTO> previewItems = userListItemService.getPreviewItems(userList.getId());
        long nestedListsCount = userListItemService.countNestedLists(userList.getId());
        double watchedPercentage = userListItemService.getWatchedPercentage(userList.getId(), viewerId);
        return userListMapper.userListToResponseDto(userList, previewItems, nestedListsCount, watchedPercentage);
    }

    private void assertCanViewLists(User target, boolean isOwner, boolean viewerFollowsTarget) {
        if (isOwner || Boolean.TRUE.equals(target.getIsProfilePublic()) || viewerFollowsTarget) {
            return;
        }

        throw new ForbiddenException("This user profile is private");
    }

    private List<UserListVisibility> visibleVisibilitiesFor(boolean viewerFollowsTarget) {
        return viewerFollowsTarget
                ? List.of(UserListVisibility.PUBLIC, UserListVisibility.FOLLOWERS)
                : List.of(UserListVisibility.PUBLIC);
    }

    @Override
    public UserListDetailedResponseDTO getUserListById(UUID viewerId, UUID listId) {
        UserList userList = userListRepository.findById(listId)
                .orElseThrow(() -> new NotFoundException("List not found"));

        assertListIsVisibleTo(viewerId, userList);

        List<UserListItemResponseDTO> items = userListItemService.getItems(listId);
        double watchedPercentage = userListItemService.getWatchedPercentage(listId, viewerId);

        return userListMapper.userListToDetailedResponseDto(userList, items, watchedPercentage);
    }

    private void assertListIsVisibleTo(UUID viewerId, UserList userList) {
        UUID ownerId = userList.getUser().getId();

        if (viewerId.equals(ownerId) || userList.getVisibility() == UserListVisibility.PUBLIC) {
            return;
        }

        if (userList.getVisibility() == UserListVisibility.FOLLOWERS && viewerFollowsTarget(viewerId, ownerId)) {
            return;
        }

        throw new ForbiddenException("This list is private");
    }

    private boolean viewerFollowsTarget(UUID viewerId, UUID targetUserId) {
        return followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, targetUserId, FollowStatus.ACCEPTED);
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
                .visibility(userListCreationDTO.visibility() != null ? userListCreationDTO.visibility() : UserListVisibility.PUBLIC)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return userListMapper.userListToResponseDto(userListRepository.save(userList), List.of(), 0L, 0.0);
    }

    @Override
    @Transactional
    public UserListDetailedResponseDTO createUserListWithItems(UUID userId, UserListBulkCreationDTO userListBulkCreationDTO) {
        User user = userRepository.getReferenceById(userId);
        LocalDateTime now = LocalDateTime.now();

        UserList userList = UserList.builder()
                .user(user)
                .name(userListBulkCreationDTO.name())
                .description(userListBulkCreationDTO.description())
                .visibility(userListBulkCreationDTO.visibility() != null ? userListBulkCreationDTO.visibility() : UserListVisibility.PUBLIC)
                .createdAt(now)
                .updatedAt(now)
                .build();

        UserList savedList = userListRepository.save(userList);

        List<UserListItemResponseDTO> items = userListBulkCreationDTO.items().stream()
                .map(content -> addContentItem(userId, savedList.getId(), content))
                .toList();
        double watchedPercentage = userListItemService.getWatchedPercentage(savedList.getId(), userId);

        return userListMapper.userListToDetailedResponseDto(savedList, items, watchedPercentage);
    }

    private UserListItemResponseDTO addContentItem(UUID userId, UUID listId, ContentRefCreationDTO content) {
        return userListItemService.addItem(userId, listId, new UserListItemCreationDTO(content, null, null, null));
    }

    @Override
    @Transactional
    public UserListResponseDTO updateUserList(UUID userId, UUID listId, UserListPatchDTO userListPatchDTO) {
        UserList userList = findOwnedList(userId, listId);

        applyPatch(userList, userListPatchDTO);
        userList.setUpdatedAt(LocalDateTime.now());

        return toResponseDto(userListRepository.save(userList), userId);
    }

    private void applyPatch(UserList userList, UserListPatchDTO userListPatchDTO) {
        if (userListPatchDTO.name() != null) {
            String newName = userListPatchDTO.name().trim();
            if (newName.isEmpty()) {
                throw new BadRequestException("Name must not be blank");
            }
            if (!newName.equals(userList.getName())) {
                userList.setName(newName);
            }
        }

        if (userListPatchDTO.description() != null && !userListPatchDTO.description().equals(userList.getDescription())) {
            userList.setDescription(userListPatchDTO.description());
        }

        if (userListPatchDTO.visibility() != null && userListPatchDTO.visibility() != userList.getVisibility()) {
            userList.setVisibility(userListPatchDTO.visibility());
        }
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