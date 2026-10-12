package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.dto.UserListDetailItemDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListDetailViewResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPage;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPageMode;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPageRequest;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;
import com.watchwise.watchwise_api.userlist.dto.UserListNestedDetailDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.service.UserListDetailViewService;
import com.watchwise.watchwise_api.userlist.service.UserListItemPageReader;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserListDetailViewServiceImpl implements UserListDetailViewService {

    private static final int NESTED_PREVIEW_LIMIT = 5;
    private static final Set<ContentCardFieldSet> CARD_FIELDS = Set.of(
            ContentCardFieldSet.BASIC_METADATA,
            ContentCardFieldSet.STATS,
            ContentCardFieldSet.VIEWER_STATE,
            ContentCardFieldSet.SOCIAL_METADATA);

    private final UserListItemPageReader pageReader;
    private final UserListItemRepository userListItemRepository;
    private final UserListItemService userListItemService;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final ContentCardAssembler contentCardAssembler;

    @Override
    @Transactional(readOnly = true)
    public UserListDetailViewResponseDTO getView(UserListItemPageRequest request) {
        if (request == null || request.mode() != UserListItemPageMode.VISUAL) {
            throw new BadRequestException("List detail view requires VISUAL mode");
        }

        UserListItemPage page = pageReader.read(request);
        List<UserListItemResponseDTO> items = page.items();
        List<UUID> nestedListIds = items.stream()
                .map(UserListItemResponseDTO::childList)
                .filter(java.util.Objects::nonNull)
                .map(child -> child.id())
                .distinct()
                .toList();
        Map<UUID, UserList> nestedLists = loadVisibleNestedLists(nestedListIds, request.viewerId());
        assertNestedListsRemainVisible(items, nestedLists);

        Map<ContentCoordinate, ContentCardSpec> specsByCoordinate = new LinkedHashMap<>();
        items.stream()
                .map(UserListItemResponseDTO::content)
                .filter(java.util.Objects::nonNull)
                .map(this::toSpec)
                .forEach(spec -> specsByCoordinate.putIfAbsent(spec.coordinate(), spec));

        List<UserListItem> nestedContentItems = nestedListIds.isEmpty()
                ? List.of()
                : userListItemRepository.findContentItemsByUserListIdInOrderByPosition(nestedListIds);
        Map<UUID, List<ContentCoordinate>> previewCoordinatesByList = new LinkedHashMap<>();
        nestedContentItems.stream()
                .collect(Collectors.groupingBy(
                        item -> item.getUserList().getId(), LinkedHashMap::new, Collectors.toList()))
                .forEach((listId, contentItems) -> {
                    List<ContentCoordinate> coordinates = contentItems.stream()
                            .limit(NESTED_PREVIEW_LIMIT)
                            .map(UserListItem::getContent)
                            .map(ContentCoordinate::from)
                            .toList();
                    previewCoordinatesByList.put(listId, coordinates);
                    contentItems.stream()
                            .limit(NESTED_PREVIEW_LIMIT)
                            .map(UserListItem::getContent)
                            .map(this::toSpec)
                            .forEach(spec -> specsByCoordinate.putIfAbsent(spec.coordinate(), spec));
                });

        User viewer = userRepository.findById(request.viewerId()).orElse(null);
        UUID ownerId = page.userList().getUser().getId();
        Map<ContentCoordinate, ContentCardDTO> cardsByCoordinate = specsByCoordinate.isEmpty()
                ? Map.of()
                : contentCardAssembler.assemble(
                        specsByCoordinate.values(),
                        new ContentCardContext(
                                viewer == null ? "en-US" : viewer.getPreferredLanguage(),
                                viewer == null ? "US" : viewer.getPreferredRegion(),
                                ownerId,
                                request.viewerId()),
                        CARD_FIELDS);

        Map<UUID, Long> itemCounts = nestedListIds.isEmpty()
                ? Map.of()
                : userListItemService.getItemsCountByListIds(nestedListIds);
        Map<UUID, UserListItemScope> itemScopes = nestedListIds.isEmpty()
                ? Map.of()
                : userListItemService.getItemScopeByListIds(nestedListIds);
        Map<UUID, Long> commentCounts = nestedListIds.isEmpty()
                ? Map.of()
                : commentRepository.countByListIdIn(nestedListIds).stream()
                        .collect(Collectors.toMap(CommentRepository.ListCommentCount::getListId,
                                CommentRepository.ListCommentCount::getCount));

        Map<UUID, UserListNestedDetailDTO> nestedDetails = new LinkedHashMap<>();
        for (UUID nestedListId : nestedListIds) {
            UserList nestedList = nestedLists.get(nestedListId);
            List<ContentCardDTO> previewCards = previewCoordinatesByList
                    .getOrDefault(nestedListId, List.of()).stream()
                    .map(cardsByCoordinate::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            nestedDetails.put(nestedListId, new UserListNestedDetailDTO(
                    preview(nestedList.getUser()),
                    nestedList.getId(),
                    nestedList.getName(),
                    nestedList.getDescription(),
                    nestedList.getVisibility(),
                    nestedList.getCreatedAt(),
                    nestedList.getUpdatedAt(),
                    itemScopes.get(nestedListId),
                    itemCounts.getOrDefault(nestedListId, 0L),
                    nestedList.getLikesCount(),
                    commentCounts.getOrDefault(nestedListId, 0L),
                    previewCards));
        }

        List<UserListDetailItemDTO> detailItems = items.stream()
                .map(item -> toDetailItem(item, cardsByCoordinate, nestedDetails))
                .toList();
        return new UserListDetailViewResponseDTO(
                page.userList().getId(),
                page.userList().getName(),
                page.userList().getDescription(),
                page.userList().getVisibility(),
                page.watchedPercentage(),
                page.userList().getCreatedAt(),
                page.userList().getUpdatedAt(),
                page.likesCount(),
                page.likedByMe(),
                page.itemsCount(),
                page.commentsCount(),
                page.totalRuntimeMinutes(),
                page.rank(),
                page.itemScope(),
                new PageResponseDTO<>(detailItems, page.page(), page.size(), page.totalElements(),
                        page.totalPages(), page.hasNext()));
    }

    private Map<UUID, UserList> loadVisibleNestedLists(Collection<UUID> nestedListIds, UUID viewerId) {
        if (nestedListIds.isEmpty()) {
            return Map.of();
        }
        return userListItemRepository.findVisibleChildListsByIdIn(nestedListIds, viewerId).stream()
                .collect(Collectors.toMap(UserList::getId, list -> list, (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private void assertNestedListsRemainVisible(
            List<UserListItemResponseDTO> items, Map<UUID, UserList> visibleLists) {
        boolean hiddenNestedList = items.stream()
                .map(UserListItemResponseDTO::childList)
                .filter(java.util.Objects::nonNull)
                .map(child -> child.id())
                .anyMatch(id -> !visibleLists.containsKey(id));
        if (hiddenNestedList) {
            throw new ForbiddenException("A nested list is no longer visible");
        }
    }

    private UserListDetailItemDTO toDetailItem(
            UserListItemResponseDTO item,
            Map<ContentCoordinate, ContentCardDTO> cardsByCoordinate,
            Map<UUID, UserListNestedDetailDTO> nestedDetails) {
        ContentCardDTO card = item.content() == null
                ? null
                : cardsByCoordinate.get(coordinate(item.content()));
        UserListNestedDetailDTO nestedList = item.childList() == null
                ? null
                : nestedDetails.get(item.childList().id());
        return new UserListDetailItemDTO(
                item.id(), card, nestedList, item.position(), item.description(), item.createdAt(), item.updatedAt(),
                item.customPosterUrl(), item.contentState(), item.episodeAverageRating(),
                item.globalEpisodeAverageRating(), item.contentAverageRating());
    }

    private ContentCardSpec toSpec(ContentRefDTO content) {
        return new ContentCardSpec(coordinate(content), null, null, null, content.runtimeMinutes(), content.genres());
    }

    private ContentCardSpec toSpec(Content content) {
        return new ContentCardSpec(ContentCoordinate.from(content), null, null, null,
                content.getRuntimeMinutes(), content.getGenres());
    }

    private ContentCoordinate coordinate(ContentRefDTO content) {
        return new ContentCoordinate(content.type(), content.tmdbId(), content.seriesTmdbId(),
                content.seasonNumber(), content.episodeNumber());
    }

    private UserPreviewDTO preview(User user) {
        return new UserPreviewDTO(user.getId(), user.getUsername(), user.getName(), user.getProfilePicture(),
                user.getIsProfilePublic());
    }
}
