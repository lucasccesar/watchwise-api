package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.search.SearchTextNormalizer;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.dto.*;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListViewRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import com.watchwise.watchwise_api.userlist.service.UserListViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserListViewServiceImpl implements UserListViewService {
    private final UserListViewRepository repository;
    private final UserListItemRepository itemRepository;
    private final UserListItemService itemService;
    private final CommentRepository comments;
    private final LikeService likes;
    private final UserRepository users;
    private final FollowerRepository followers;
    private final ContentCardAssembler contentCards;
    private final PageRequestFactory pages;

    @Override
    @Transactional(readOnly = true)
    public Page<UserListCardDTO> getView(UserListViewRequest request) {
        UserListViewQuery query = validateAndBuild(request);
        if (query.mode() == UserListViewQueryMode.USER) assertProfileVisible(query);
        Page<UserList> page = repository.findPage(query);
        if (page.isEmpty()) return page.map(ignored -> (UserListCardDTO) null);

        List<UserList> lists = page.getContent();
        List<UUID> ids = lists.stream().map(UserList::getId).toList();
        Map<UUID, List<ContentRefDTO>> rawPreviews = itemService.getPreviewItemsByListIds(ids);
        Map<UUID, Long> nestedCounts = itemService.countNestedListsByListIds(ids);
        Map<UUID, Long> itemsCounts = itemService.getItemsCountByListIds(ids);
        Map<UUID, UserListItemScope> scopes = itemService.getItemScopeByListIds(ids, nestedCounts);
        Set<UUID> likedIds = likes.getLikedListIds(query.viewerId(), ids);
        Map<UUID, Long> commentCounts = comments.countVisibleListComments(query.viewerId(), ids).stream()
                .collect(Collectors.toMap(CommentRepository.ListCommentCount::getListId,
                        CommentRepository.ListCommentCount::getCount));
        Map<UUID, Long> likeCounts = lists.stream().collect(Collectors.toMap(UserList::getId,
                list -> list.getLikesCount() == null ? 0L : list.getLikesCount().longValue()));

        Set<UUID> ownerIds = lists.stream().map(list -> list.getUser().getId()).collect(Collectors.toSet());
        ownerIds.add(query.viewerId());
        Map<UUID, User> loadedUsers = new HashMap<>();
        users.findAllById(ownerIds).forEach(user -> loadedUsers.put(user.getId(), user));
        Map<UUID, UserPreviewDTO> ownerPreviews = loadedUsers.values().stream()
                .collect(Collectors.toMap(User::getId, this::preview));

        Map<UUID, List<UserListPreviewDTO>> nestedPreviews = nestedPreviews(ids, query.viewerId());
        Map<UUID, List<ContentCardDTO>> previewCards = previewCards(rawPreviews, loadedUsers.get(query.viewerId()));
        List<UserListCardDTO> cards = UserListViewAssembler.assemble(lists, ownerPreviews, previewCards,
                nestedPreviews, nestedCounts, likeCounts, itemsCounts, commentCounts,
                likedIds == null ? Set.of() : likedIds, scopes, query.viewerId());
        return new org.springframework.data.domain.PageImpl<>(cards, page.getPageable(), page.getTotalElements());
    }

    private UserListViewQuery validateAndBuild(UserListViewRequest request) {
        if (request == null || request.viewerId() == null || request.mode() == null)
            throw new BadRequestException("Viewer and mode are required");
        UserListViewSource source = request.mode() == UserListViewQueryMode.MY && request.source() == null
                ? UserListViewSource.ALL : request.source();
        switch (request.mode()) {
            case MY -> {
                if (request.ownerId() != null)
                    throw new BadRequestException("MY rejects ownerId");
            }
            case USER -> {
                if (request.ownerId() == null || request.source() != null)
                    throw new BadRequestException("USER requires ownerId and rejects source");
            }
            case DISCOVER -> {
                if (request.ownerId() != null || request.source() != null)
                    throw new BadRequestException("DISCOVER rejects ownerId and source");
            }
        }
        if (request.sortDirection() != null && !Set.of("asc", "desc").contains(request.sortDirection()))
            throw new BadRequestException("sortDirection must be one of: asc, desc");
        UserListViewSort sort = request.sortBy() == null ? null : UserListViewSort.fromApiValue(request.sortBy());
        if (sort != null && (sort == UserListViewSort.UPDATED_AT || sort == UserListViewSort.RANK)
                && !(request.mode() == UserListViewQueryMode.MY && source == UserListViewSource.OWNED))
            throw new BadRequestException("Sort is unavailable for this list view");
        if (request.size() != null && request.size() < 10)
            throw new BadRequestException("Page size must be at least 10");
        PageRequest pageable = pages.build(request.page(), request.size(), 10);
        String normalizedQuery = SearchTextNormalizer.normalizeOptionalQuery(request.query());
        String genre = request.genre() == null || request.genre().isBlank() ? null : request.genre().trim();
        return new UserListViewQuery(request.viewerId(), request.ownerId(), request.mode(), source,
                normalizedQuery, request.itemType(), genre, sort,
                "desc".equals(request.sortDirection()) ? Sort.Direction.DESC : Sort.Direction.ASC, pageable);
    }

    private void assertProfileVisible(UserListViewQuery query) {
        User owner = users.findById(query.ownerId()).orElseThrow(() -> new NotFoundException("User not found"));
        if (!query.viewerId().equals(query.ownerId()) && !Boolean.TRUE.equals(owner.getIsProfilePublic())
                && !followers.existsByFollowerIdAndFollowedIdAndStatus(
                        query.viewerId(), query.ownerId(), FollowStatus.ACCEPTED))
            throw new ForbiddenException("This user profile is private");
    }

    private Map<UUID, List<ContentCardDTO>> previewCards(Map<UUID, List<ContentRefDTO>> previews, User viewer) {
        if (previews == null || previews.isEmpty()) return Map.of();
        Map<ContentCoordinate, ContentCardSpec> specs = new LinkedHashMap<>();
        Map<UUID, List<ContentCoordinate>> coordinatesByList = new LinkedHashMap<>();
        previews.forEach((id, refs) -> {
            List<ContentCoordinate> coordinates = new ArrayList<>();
            for (ContentRefDTO ref : refs.stream().limit(5).toList()) {
                ContentCoordinate coordinate = new ContentCoordinate(ref.type(), ref.tmdbId(), ref.seriesTmdbId(),
                        ref.seasonNumber(), ref.episodeNumber());
                coordinates.add(coordinate);
                specs.putIfAbsent(coordinate, new ContentCardSpec(coordinate, null, null, null,
                        ref.runtimeMinutes(), ref.genres()));
            }
            coordinatesByList.put(id, coordinates);
        });
        if (specs.isEmpty()) return Map.of();
        String language = viewer == null ? "en-US" : viewer.getPreferredLanguage();
        String region = viewer == null ? "US" : viewer.getPreferredRegion();
        Map<ContentCoordinate, ContentCardDTO> cards = contentCards.assemble(specs.values(),
                new ContentCardContext(language, region, null, null), ContentCardFieldSet.BASIC_METADATA);
        Map<UUID, List<ContentCardDTO>> result = new HashMap<>();
        coordinatesByList.forEach((id, coordinates) -> result.put(id, coordinates.stream()
                .map(cards::get).filter(Objects::nonNull).toList()));
        return result;
    }

    private Map<UUID, List<UserListPreviewDTO>> nestedPreviews(List<UUID> parentIds, UUID viewerId) {
        List<UserListItemRepository.NestedPreview> rows = itemRepository.findNestedPreviewsByParentIdIn(parentIds, viewerId);
        if (rows.isEmpty()) return Map.of();
        Set<UUID> followedIds = rows.stream().anyMatch(row -> "FOLLOWERS".equals(row.getVisibility()))
                ? new HashSet<>(followers.findFollowedIdsByFollowerIdAndStatus(viewerId, FollowStatus.ACCEPTED))
                : Set.of();
        Map<UUID, List<UserListPreviewDTO>> result = new HashMap<>();
        for (UserListItemRepository.NestedPreview row : rows) {
            UserListVisibility visibility = UserListVisibility.valueOf(row.getVisibility());
            if (!viewerId.equals(row.getOwnerId()) && visibility != UserListVisibility.PUBLIC
                    && !(visibility == UserListVisibility.FOLLOWERS && followedIds.contains(row.getOwnerId()))) continue;
            UserPreviewDTO owner = new UserPreviewDTO(row.getOwnerId(), row.getUsername(), row.getOwnerName(),
                    row.getProfilePicture(), row.getProfilePublic());
            result.computeIfAbsent(row.getParentId(), ignored -> new ArrayList<>())
                    .add(new UserListPreviewDTO(row.getChildId(), owner, row.getChildName(), visibility));
        }
        return result;
    }

    private UserPreviewDTO preview(User user) {
        return new UserPreviewDTO(user.getId(), user.getUsername(), user.getName(),
                user.getProfilePicture(), user.getIsProfilePublic());
    }
}
