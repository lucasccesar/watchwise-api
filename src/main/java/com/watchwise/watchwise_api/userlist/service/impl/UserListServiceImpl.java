package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.dto.UserListBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListDetailedResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemBulkCreationDTO;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserListServiceImpl implements UserListService {

    private final UserListRepository userListRepository;
    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final UserListItemService userListItemService;
    private final UserListMapper userListMapper;
    private final LikeService likeService;
    private final PageRequestFactory pageRequestFactory;
    private final CommentRepository commentRepository;

    @Override
    public Page<UserListResponseDTO> getUserLists(UUID viewerId, UUID userId, Integer pageNumber, Integer pageSize) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        boolean isOwner = viewerId.equals(userId);
        boolean viewerFollowsTarget = !isOwner && viewerFollowsTarget(viewerId, userId);

        assertCanViewLists(target, isOwner, viewerFollowsTarget);

        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);

        Page<UserList> lists = isOwner
                ? userListRepository.findByUserId(userId, pageRequest)
                : userListRepository.findByUserIdAndVisibilityIn(userId, visibleVisibilitiesFor(viewerFollowsTarget), pageRequest);

        List<UUID> listIds = lists.getContent().stream().map(UserList::getId).toList();
        Map<UUID, List<ContentRefDTO>> previewsByListId = userListItemService.getPreviewItemsByListIds(listIds);
        Map<UUID, Long> nestedListsCountByListId = userListItemService.countNestedListsByListIds(listIds);
        Map<UUID, Double> watchedPercentageByListId = userListItemService.getWatchedPercentagesByListIds(listIds, viewerId);
        Set<UUID> likedListIds = likeService.getLikedListIds(viewerId, listIds);
        Map<UUID, Long> itemsCountByListId = userListItemService.getItemsCountByListIds(listIds);
        Map<UUID, Long> totalRuntimeMinutesByListId = userListItemService.getTotalRuntimeMinutesByListIds(listIds);
        Map<UUID, Long> commentsCountByListId = commentsCountByListIds(listIds);

        return lists.map(list -> userListMapper.userListToResponseDto(
                list,
                previewsByListId.getOrDefault(list.getId(), List.of()),
                nestedListsCountByListId.getOrDefault(list.getId(), 0L),
                watchedPercentageByListId.getOrDefault(list.getId(), 0.0),
                likedListIds.contains(list.getId()),
                itemsCountByListId.getOrDefault(list.getId(), 0L),
                commentsCountByListId.getOrDefault(list.getId(), 0L),
                totalRuntimeMinutesByListId.getOrDefault(list.getId(), 0L)));
    }

    private Map<UUID, Long> commentsCountByListIds(Collection<UUID> listIds) {
        if (listIds.isEmpty()) {
            return Map.of();
        }

        return commentRepository.countByListIdIn(listIds).stream()
                .collect(Collectors.toMap(
                        CommentRepository.ListCommentCount::getListId,
                        CommentRepository.ListCommentCount::getCount));
    }

    private UserListResponseDTO toResponseDto(UserList userList, UUID viewerId) {
        List<ContentRefDTO> previewItems = userListItemService.getPreviewItems(userList.getId());
        long nestedListsCount = userListItemService.countNestedLists(userList.getId());
        double watchedPercentage = userListItemService.getWatchedPercentage(userList.getId(), viewerId);
        boolean likedByMe = likeService.getLikedListIds(viewerId, List.of(userList.getId())).contains(userList.getId());
        long itemsCount = userListItemService.getItemsCount(userList.getId());
        long totalRuntimeMinutes = userListItemService.getTotalRuntimeMinutes(userList.getId());
        long commentsCount = commentRepository.countByListId(userList.getId());
        return userListMapper.userListToResponseDto(userList, previewItems, nestedListsCount, watchedPercentage, likedByMe,
                itemsCount, commentsCount, totalRuntimeMinutes);
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

        List<UserListItemResponseDTO> items = userListItemService.getItems(viewerId, listId);
        double watchedPercentage = userListItemService.getWatchedPercentage(listId, viewerId);
        boolean likedByMe = likeService.getLikedListIds(viewerId, List.of(listId)).contains(listId);
        long totalRuntimeMinutes = userListItemService.getTotalRuntimeMinutes(listId);
        long commentsCount = commentRepository.countByListId(listId);

        return userListMapper.userListToDetailedResponseDto(userList, items, watchedPercentage, likedByMe,
                items.size(), commentsCount, totalRuntimeMinutes);
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

        return userListMapper.userListToResponseDto(userListRepository.save(userList), List.of(), 0L, 0.0, false, 0L, 0L, 0L);
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

        List<UserListItemResponseDTO> items = userListItemService.addItems(
                userId, savedList.getId(), new UserListItemBulkCreationDTO(userListBulkCreationDTO.items()));
        double watchedPercentage = userListItemService.getWatchedPercentage(savedList.getId(), userId);
        long totalRuntimeMinutes = items.stream()
                .filter(item -> item.content() != null && item.content().runtimeMinutes() != null)
                .mapToLong(item -> item.content().runtimeMinutes())
                .sum();

        return userListMapper.userListToDetailedResponseDto(savedList, items, watchedPercentage, false,
                items.size(), 0L, totalRuntimeMinutes);
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

        userListItemService.removeItemsReferencingChildList(listId);
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
}