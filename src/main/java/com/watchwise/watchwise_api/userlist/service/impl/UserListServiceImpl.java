package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.diaryentry.dto.SeasonProgressDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.dto.UserListBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListDetailedResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;
import com.watchwise.watchwise_api.userlist.dto.UserListPatchDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListProgressItemDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListProgressResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListResponseDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.mapper.UserListMapper;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import com.watchwise.watchwise_api.userlist.service.UserListItemsWithState;
import com.watchwise.watchwise_api.userlist.service.UserListService;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressReader;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class UserListServiceImpl implements UserListService {

    private final UserListRepository userListRepository;
    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final UserListItemService userListItemService;
    private final UserListMapper userListMapper;
    private final ContentMapper contentMapper;
    private final LikeService likeService;
    private final PageRequestFactory pageRequestFactory;
    private final CommentRepository commentRepository;
    private final LikeRepository likeRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final UserListItemRepository userListItemRepository;
    private final SeriesProgressReader seriesProgressReader;
    private final UserContentPosterService userContentPosterService;

    static final int RANK_PARK_OFFSET = 1_000_000_000;
    private static final int USER_LIST_PAGE_SIZE = 10;
    private static final Set<String> GENERIC_SORT_FIELDS = Set.of("rank", "updatedAt", "name", "likesCount");
    private static final Set<String> AGGREGATE_SORT_FIELDS = Set.of("itemsCount", "commentsCount");

    @Override
    public Page<UserListResponseDTO> getUserLists(UUID viewerId, UUID userId, Integer pageNumber, Integer pageSize,
            String sortBy, String sortDirection, UUID contentId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        boolean isOwner = viewerId.equals(userId);
        boolean viewerFollowsTarget = !isOwner && viewerFollowsTarget(viewerId, userId);

        assertCanViewLists(target, isOwner, viewerFollowsTarget);

        if (sortBy != null && !GENERIC_SORT_FIELDS.contains(sortBy) && !AGGREGATE_SORT_FIELDS.contains(sortBy)) {
            throw new BadRequestException("sortBy must be one of: rank, updatedAt, name, likesCount, itemsCount, commentsCount");
        }
        assertValidSortDirection(sortDirection);
        validateUserListPageSize(pageSize);

        List<UserListVisibility> visibilities = isOwner
                ? List.of(UserListVisibility.values())
                : visibleVisibilitiesFor(viewerFollowsTarget);

        Page<UserList> lists;
        if (sortBy != null && AGGREGATE_SORT_FIELDS.contains(sortBy)) {
            PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize, USER_LIST_PAGE_SIZE);
            String direction = "desc".equals(sortDirection) ? "DESC" : "ASC";
            List<String> visibilityNames = visibilities.stream().map(Enum::name).toList();
            lists = "itemsCount".equals(sortBy)
                    ? userListRepository.findByUserIdOrderByItemsCount(userId, visibilityNames, direction, pageRequest)
                    : userListRepository.findByUserIdOrderByCommentsCount(userId, visibilityNames, direction, pageRequest);
        } else {
            PageRequest pageRequest = pageRequestFactory.build(
                    pageNumber, pageSize, USER_LIST_PAGE_SIZE, sortBy, sortDirection);
            lists = isOwner
                    ? userListRepository.findByUserId(userId, pageRequest)
                    : userListRepository.findByUserIdAndVisibilityIn(userId, visibilities, pageRequest);
        }

        return mapToResponseDtoPage(lists, viewerId, contentId);
    }

    @Override
    public Page<UserListResponseDTO> getDiscoverLists(UUID viewerId, Integer pageNumber, Integer pageSize) {
        validateUserListPageSize(pageSize);
        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize, USER_LIST_PAGE_SIZE);
        Page<UserList> lists = userListRepository
                .findByVisibilityOrderByLikesCountDescCreatedAtDescIdDesc(UserListVisibility.PUBLIC, pageRequest);
        return mapToResponseDtoPage(lists, viewerId, null);
    }

    private void validateUserListPageSize(Integer pageSize) {
        if (pageSize != null && pageSize < USER_LIST_PAGE_SIZE) {
            throw new BadRequestException("Page size must be greater than or equal to " + USER_LIST_PAGE_SIZE);
        }
    }

    @Override
    public Page<UserListResponseDTO> getLikedLists(UUID userId, Integer pageNumber, Integer pageSize) {
        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);
        Page<UserList> lists = likeRepository.findLikedListsByUserId(userId, pageRequest);

        return mapToResponseDtoPage(lists, userId, null);
    }

    private Page<UserListResponseDTO> mapToResponseDtoPage(Page<UserList> lists, UUID viewerId, UUID contentId) {
        List<UUID> listIds = lists.getContent().stream().map(UserList::getId).toList();
        Map<UUID, List<ContentRefDTO>> previewsByListId = userListItemService.getPreviewItemsByListIds(listIds);
        Map<UUID, Long> nestedListsCountByListId = userListItemService.countNestedListsByListIds(listIds);
        Map<UUID, Double> watchedPercentageByListId = userListItemService.getWatchedPercentagesByListIds(listIds, viewerId);
        Set<UUID> likedListIds = likeService.getLikedListIds(viewerId, listIds);
        Map<UUID, Long> itemsCountByListId = userListItemService.getItemsCountByListIds(listIds);
        Map<UUID, Long> totalRuntimeMinutesByListId = userListItemService.getTotalRuntimeMinutesByListIds(listIds);
        Map<UUID, Long> commentsCountByListId = commentsCountByListIds(listIds);
        Map<UUID, UserListItemScope> itemScopeByListId =
                userListItemService.getItemScopeByListIds(listIds, nestedListsCountByListId);
        Set<UUID> listIdsContainingContent = contentId != null
                ? userListItemService.getListIdsContainingContent(listIds, contentId)
                : null;

        return lists.map(list -> userListMapper.userListToResponseDto(
                list,
                previewsByListId.getOrDefault(list.getId(), List.of()),
                nestedListsCountByListId.getOrDefault(list.getId(), 0L),
                watchedPercentageByListId.getOrDefault(list.getId(), 0.0),
                likedListIds.contains(list.getId()),
                itemsCountByListId.getOrDefault(list.getId(), 0L),
                commentsCountByListId.getOrDefault(list.getId(), 0L),
                totalRuntimeMinutesByListId.getOrDefault(list.getId(), 0L),
                itemScopeByListId.get(list.getId()),
                listIdsContainingContent == null ? null : listIdsContainingContent.contains(list.getId())));
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
        UserListItemScope itemScope = userListItemService
                .getItemScopeByListIds(List.of(userList.getId()), Map.of(userList.getId(), nestedListsCount))
                .get(userList.getId());
        return userListMapper.userListToResponseDto(userList, previewItems, nestedListsCount, watchedPercentage, likedByMe,
                itemsCount, commentsCount, totalRuntimeMinutes, itemScope, null);
    }

    private void assertValidSortDirection(String sortDirection) {
        if (sortDirection != null && !sortDirection.equals("asc") && !sortDirection.equals("desc")) {
            throw new BadRequestException("sortDirection must be one of: asc, desc");
        }
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

    private static final Set<String> ITEM_SORT_FIELDS = Set.of(
            "position", "dateAdded", "duration", "episodeAvgRating", "globalEpisodeAvgRating", "contentAvgRating");

    @Override
    public UserListDetailedResponseDTO getUserListById(UUID viewerId, UUID listId, ContentType type, String genre,
            String sortBy, String sortDirection) {
        UserList userList = userListRepository.findById(listId)
                .orElseThrow(() -> new NotFoundException("List not found"));

        assertListIsVisibleTo(viewerId, userList);

        if (sortBy != null && !ITEM_SORT_FIELDS.contains(sortBy)) {
            throw new BadRequestException(
                    "sortBy must be one of: position, dateAdded, duration, episodeAvgRating, globalEpisodeAvgRating, contentAvgRating");
        }
        assertValidSortDirection(sortDirection);

        boolean canViewOwnerRatings = canViewOwnerRatings(viewerId, userList.getUser());
        if ("episodeAvgRating".equals(sortBy) && !canViewOwnerRatings) {
            throw new ForbiddenException("The list owner's episode ratings are private");
        }

        UserListItemsWithState itemsWithState = userListItemService.getItemsWithState(viewerId, listId);
        List<UserListItemResponseDTO> allItems = itemsWithState.items();
        List<UserListItemResponseDTO> items = filterAndSortItems(
                allItems, type, genre, sortBy, sortDirection, userList.getUser().getId(), canViewOwnerRatings);
        double watchedPercentage = itemsWithState.watchedPercentage();
        boolean likedByMe = likeService.getLikedListIds(viewerId, List.of(listId)).contains(listId);
        long totalRuntimeMinutes = userListItemService.getTotalRuntimeMinutes(listId);
        long commentsCount = commentRepository.countByListId(listId);
        UserListItemScope itemScope = userListItemService.getItemScope(listId);

        return userListMapper.userListToDetailedResponseDto(userList, items, watchedPercentage, likedByMe,
                allItems.size(), commentsCount, totalRuntimeMinutes, itemScope);
    }

    @Override
    public UserListProgressResponseDTO getUserListProgress(UUID viewerId, UUID listId) {
        UserList userList = userListRepository.findById(listId)
                .orElseThrow(() -> new NotFoundException("List not found"));

        assertListIsVisibleTo(viewerId, userList);

        List<UserListItem> items = userListItemRepository
                .findByUserListIdWithContentAndChildListOrderByPositionAsc(listId);
        if (items.stream().anyMatch(item -> item.getChildList() != null)) {
            throw new BadRequestException("List progress is only available for content lists");
        }

        List<UserListItem> contentItems = items.stream()
                .filter(item -> item.getContent() != null)
                .toList();
        Map<UUID, String> customPosterByContentId = loadPostersForOwner(userList.getUser().getId(), contentItems);
        Set<UUID> directContentIds = contentItems.stream()
                .filter(item -> item.getContent().getType() == ContentType.MOVIE
                        || item.getContent().getType() == ContentType.EPISODE)
                .map(UserListItem::getContent)
                .map(Content::getId)
                .collect(Collectors.toSet());
        Set<UUID> watchedDirectContentIds = directContentIds.isEmpty()
                ? Set.of()
                : diaryEntryRepository.findWatchedDirectContentIds(viewerId, directContentIds);

        List<String> seriesTmdbIds = contentItems.stream()
                .map(UserListItem::getContent)
                .filter(content -> content.getType() == ContentType.SERIES
                        || content.getType() == ContentType.SEASON)
                .map(this::seriesTmdbIdForProgress)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, SeriesInProgressResponseDTO> seriesProgressById = seriesTmdbIds.isEmpty()
                ? Map.of()
                : seriesProgressReader.readForSeriesIds(viewerId, seriesTmdbIds);

        List<UserListProgressItemDTO> progressItems = new ArrayList<>();
        long watchedItems = 0L;
        for (UserListItem item : contentItems) {
            Content content = item.getContent();
            SeriesInProgressResponseDTO seriesProgress = null;
            SeasonProgressDTO seasonProgress = null;
            boolean watched = false;

            if (content.getType() == ContentType.MOVIE || content.getType() == ContentType.EPISODE) {
                watched = watchedDirectContentIds.contains(content.getId());
            } else if (content.getType() == ContentType.SERIES) {
                seriesProgress = seriesProgressById.get(content.getTmdbId());
                watched = isComplete(seriesProgress == null ? null : seriesProgress.watchedPercentage());
            } else if (content.getType() == ContentType.SEASON) {
                seriesProgress = seriesProgressById.get(content.getSeriesTmdbId());
                seasonProgress = findSeasonProgress(seriesProgress, content.getSeasonNumber());
                watched = isComplete(seasonProgress == null ? null : seasonProgress.watchedPercentage());
            }

            if (watched) {
                watchedItems++;
            }
            progressItems.add(new UserListProgressItemDTO(
                    item.getId(),
                    contentMapper.contentToContentRefDto(content),
                    item.getPosition(),
                    item.getDescription(),
                    item.getCreatedAt(),
                    item.getUpdatedAt(),
                    customPosterByContentId.get(content.getId()),
                    content.getType() == ContentType.SERIES ? seriesProgress : null,
                    content.getType() == ContentType.SEASON ? seasonProgress : null));
        }

        long totalItems = contentItems.size();
        double watchedPercentage = totalItems == 0 ? 0.0 : watchedItems * 100.0 / totalItems;
        long commentsCount = commentRepository.countByListId(listId);
        long totalRuntimeMinutes = userListItemService.getTotalRuntimeMinutes(listId);
        boolean likedByMe = likeService.getLikedListIds(viewerId, List.of(listId)).contains(listId);
        UserListItemScope itemScope = userListItemService.getItemScope(listId);

        return new UserListProgressResponseDTO(
                userList.getId(),
                userList.getName(),
                userList.getDescription(),
                userList.getVisibility(),
                userList.getCreatedAt(),
                userList.getUpdatedAt(),
                userList.getLikesCount(),
                likedByMe,
                items.size(),
                commentsCount,
                totalRuntimeMinutes,
                userList.getRank(),
                itemScope,
                totalItems,
                watchedItems,
                watchedPercentage,
                progressItems);
    }

    private Map<UUID, String> loadPostersForOwner(UUID ownerId, List<UserListItem> items) {
        List<UUID> contentIds = items.stream()
                .map(UserListItem::getContent)
                .filter(Objects::nonNull)
                .map(Content::getId)
                .distinct()
                .toList();
        return contentIds.isEmpty()
                ? Map.of()
                : userContentPosterService.findByUserAndContentIds(ownerId, contentIds);
    }

    private String seriesTmdbIdForProgress(Content content) {
        return content.getType() == ContentType.SERIES
                ? content.getTmdbId()
                : content.getSeriesTmdbId();
    }

    private SeasonProgressDTO findSeasonProgress(
            SeriesInProgressResponseDTO seriesProgress, Integer seasonNumber) {
        if (seriesProgress == null || seasonNumber == null) {
            return null;
        }
        return seriesProgress.seasonProgress().stream()
                .filter(season -> Objects.equals(season.seasonNumber(), seasonNumber))
                .findFirst()
                .orElse(null);
    }

    private boolean isComplete(Double percentage) {
        return percentage != null && percentage >= 100.0;
    }

    private UserListItemScope resolveItemScopeFromLoadedItems(List<UserListItemResponseDTO> items) {
        boolean hasNestedLists = items.stream().anyMatch(item -> item.childList() != null);
        Set<ContentType> types = items.stream()
                .filter(item -> item.content() != null)
                .map(item -> item.content().type())
                .collect(Collectors.toSet());
        return UserListItemScope.resolve(types, hasNestedLists);
    }

    private List<UserListItemResponseDTO> filterAndSortItems(List<UserListItemResponseDTO> items, ContentType type,
            String genre, String sortBy, String sortDirection, UUID ownerId, boolean includeOwnerRatings) {
        Stream<UserListItemResponseDTO> stream = items.stream();

        if (type != null) {
            stream = stream.filter(item -> item.content() != null && item.content().type() == type);
        }
        if (genre != null) {
            stream = stream.filter(item -> item.content() != null
                    && item.content().genres() != null && item.content().genres().contains(genre));
        }

        List<UserListItemResponseDTO> filtered = enrichRatingAverages(stream.toList(), ownerId, includeOwnerRatings);

        if (sortBy == null) {
            return filtered;
        }

        Comparator<UserListItemResponseDTO> comparator = switch (sortBy) {
            case "episodeAvgRating" -> ratingComparator(
                    UserListItemResponseDTO::episodeAverageRating, sortDirection);
            case "globalEpisodeAvgRating" -> ratingComparator(
                    UserListItemResponseDTO::globalEpisodeAverageRating, sortDirection);
            case "contentAvgRating" -> ratingComparator(
                    UserListItemResponseDTO::contentAverageRating, sortDirection);
            case "dateAdded" -> Comparator.comparing(UserListItemResponseDTO::createdAt);
            case "duration" -> Comparator.comparing(item -> durationMinutes(item.content()));
            default -> Comparator.comparing(UserListItemResponseDTO::position, Comparator.nullsLast(Comparator.naturalOrder()));
        };

        if ("desc".equals(sortDirection) && !isRatingSort(sortBy)) {
            comparator = comparator.reversed();
        }

        return filtered.stream().sorted(comparator).toList();
    }

    private int durationMinutes(ContentRefDTO content) {
        return content == null || content.runtimeMinutes() == null ? 0 : content.runtimeMinutes();
    }

    private List<UserListItemResponseDTO> enrichRatingAverages(
            List<UserListItemResponseDTO> items, UUID ownerId, boolean includeOwnerRatings) {
        Set<String> seriesTmdbIds = items.stream()
                .map(UserListItemResponseDTO::content)
                .filter(content -> content != null
                        && (content.type() == ContentType.SERIES || content.type() == ContentType.SEASON
                                || content.type() == ContentType.EPISODE))
                .map(content -> content.type() == ContentType.SERIES ? content.tmdbId() : content.seriesTmdbId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, Double> ownerEpisodeAverages = Map.of();
        Map<UUID, Double> publicEpisodeAverages = Map.of();
        if (!seriesTmdbIds.isEmpty()) {
            if (includeOwnerRatings) {
                List<DiaryEntry> ownerEntries =
                        diaryEntryRepository.findScoredEpisodeEntriesByUserIdAndSeriesTmdbIdIn(
                                ownerId, seriesTmdbIds);
                ownerEpisodeAverages = computeEpisodeAverageRatings(ownerEntries, items);
            }
            List<DiaryEntryRepository.PublicEpisodeRatingAggregate> publicAggregates =
                    diaryEntryRepository.findPublicEpisodeRatingAggregatesBySeriesTmdbIdIn(seriesTmdbIds);
            publicEpisodeAverages = computePublicEpisodeAverageRatings(publicAggregates, items);
        }

        Set<UUID> contentIds = items.stream()
                .map(UserListItemResponseDTO::content)
                .filter(Objects::nonNull)
                .map(ContentRefDTO::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Double> contentAverages = new HashMap<>();
        if (!contentIds.isEmpty()) {
            for (DiaryEntryRepository.ContentStats stats
                    : diaryEntryRepository.findContentStatsByContentIdIn(contentIds)) {
                contentAverages.put(stats.getContentId(), stats.getAverageScore());
            }
        }

        Map<UUID, Double> finalOwnerEpisodeAverages = ownerEpisodeAverages;
        Map<UUID, Double> finalPublicEpisodeAverages = publicEpisodeAverages;
        return items.stream()
                .map(item -> item.withRatingAverages(
                        finalOwnerEpisodeAverages.get(item.id()),
                        finalPublicEpisodeAverages.get(item.id()),
                        item.content() == null ? null : contentAverages.get(item.content().id())))
                .toList();
    }

    private Map<UUID, Double> computeEpisodeAverageRatings(
            List<DiaryEntry> scoredEpisodes, List<UserListItemResponseDTO> items) {

        Map<String, List<Integer>> scoresBySeries = new HashMap<>();
        Map<String, List<Integer>> scoresBySeason = new HashMap<>();
        Map<String, List<Integer>> scoresByEpisode = new HashMap<>();

        for (DiaryEntry entry : scoredEpisodes) {
            String seriesTmdbId = entry.getContent().getSeriesTmdbId();
            Integer seasonNumber = entry.getContent().getSeasonNumber();
            Integer episodeNumber = entry.getContent().getEpisodeNumber();
            String seasonKey = seriesTmdbId + "|" + seasonNumber;
            String episodeKey = seasonKey + "|" + episodeNumber;

            scoresBySeries.computeIfAbsent(seriesTmdbId, key -> new ArrayList<>()).add(entry.getScore());
            scoresBySeason.computeIfAbsent(seasonKey, key -> new ArrayList<>()).add(entry.getScore());
            scoresByEpisode.computeIfAbsent(episodeKey, key -> new ArrayList<>()).add(entry.getScore());
        }

        Map<UUID, Double> result = new HashMap<>();
        for (UserListItemResponseDTO item : items) {
            ContentRefDTO content = item.content();
            if (content == null) {
                continue;
            }

            List<Integer> scores = switch (content.type()) {
                case SERIES -> scoresBySeries.get(content.tmdbId());
                case SEASON -> scoresBySeason.get(content.seriesTmdbId() + "|" + content.seasonNumber());
                case EPISODE -> scoresByEpisode.get(
                        content.seriesTmdbId() + "|" + content.seasonNumber() + "|" + content.episodeNumber());
                default -> null;
            };

            if (scores != null && !scores.isEmpty()) {
                double average = scores.stream().mapToInt(Integer::intValue).average().orElseThrow();
                result.put(item.id(), average);
            }
        }

        return result;
    }

    private Map<UUID, Double> computePublicEpisodeAverageRatings(
            List<DiaryEntryRepository.PublicEpisodeRatingAggregate> aggregates,
            List<UserListItemResponseDTO> items) {
        Map<String, EpisodeRatingTotals> totalsBySeries = new HashMap<>();
        Map<String, EpisodeRatingTotals> totalsBySeason = new HashMap<>();
        Map<String, EpisodeRatingTotals> totalsByEpisode = new HashMap<>();

        for (DiaryEntryRepository.PublicEpisodeRatingAggregate aggregate : aggregates) {
            String seriesTmdbId = aggregate.getSeriesTmdbId();
            String seasonKey = seriesTmdbId + "|" + aggregate.getSeasonNumber();
            String episodeKey = seasonKey + "|" + aggregate.getEpisodeNumber();
            EpisodeRatingTotals totals = new EpisodeRatingTotals(aggregate.getScoreSum(), aggregate.getScoreCount());
            totalsBySeries.merge(seriesTmdbId, totals, EpisodeRatingTotals::plus);
            totalsBySeason.merge(seasonKey, totals, EpisodeRatingTotals::plus);
            totalsByEpisode.merge(episodeKey, totals, EpisodeRatingTotals::plus);
        }

        Map<UUID, Double> result = new HashMap<>();
        for (UserListItemResponseDTO item : items) {
            ContentRefDTO content = item.content();
            if (content == null) {
                continue;
            }

            EpisodeRatingTotals totals = switch (content.type()) {
                case SERIES -> totalsBySeries.get(content.tmdbId());
                case SEASON -> totalsBySeason.get(content.seriesTmdbId() + "|" + content.seasonNumber());
                case EPISODE -> totalsByEpisode.get(
                        content.seriesTmdbId() + "|" + content.seasonNumber() + "|" + content.episodeNumber());
                default -> null;
            };
            if (totals != null) {
                result.put(item.id(), totals.average());
            }
        }
        return result;
    }

    private record EpisodeRatingTotals(long scoreSum, long scoreCount) {
        EpisodeRatingTotals plus(EpisodeRatingTotals other) {
            return new EpisodeRatingTotals(scoreSum + other.scoreSum, scoreCount + other.scoreCount);
        }

        double average() {
            return (double) scoreSum / scoreCount;
        }
    }

    private Comparator<UserListItemResponseDTO> ratingComparator(
            Function<UserListItemResponseDTO, Double> ratingExtractor, String sortDirection) {
        Comparator<Double> valueComparator = "desc".equals(sortDirection)
                ? Comparator.<Double>naturalOrder().reversed()
                : Comparator.naturalOrder();
        return Comparator.comparing(ratingExtractor, Comparator.nullsLast(valueComparator));
    }

    private boolean isRatingSort(String sortBy) {
        return "episodeAvgRating".equals(sortBy)
                || "globalEpisodeAvgRating".equals(sortBy)
                || "contentAvgRating".equals(sortBy);
    }

    private boolean canViewOwnerRatings(UUID viewerId, User owner) {
        return viewerId.equals(owner.getId())
                || Boolean.TRUE.equals(owner.getIsProfilePublic())
                || viewerFollowsTarget(viewerId, owner.getId());
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
                .rank((int) userListRepository.countByUserIdAndRankIsNotNull(userId) + 1)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return userListMapper.userListToResponseDto(userListRepository.save(userList), List.of(), 0L, 0.0, false, 0L, 0L, 0L, null, null);
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
                .rank((int) userListRepository.countByUserIdAndRankIsNotNull(userId) + 1)
                .createdAt(now)
                .updatedAt(now)
                .build();

        UserList savedList = userListRepository.save(userList);

        UserListItemsWithState itemsWithState = userListItemService.addItemsWithState(
                userId, savedList.getId(), new UserListItemBulkCreationDTO(userListBulkCreationDTO.items()));
        List<UserListItemResponseDTO> items = itemsWithState.items();
        double watchedPercentage = itemsWithState.watchedPercentage();
        long totalRuntimeMinutes = userListItemService.getTotalRuntimeMinutes(savedList.getId());
        UserListItemScope itemScope = resolveItemScopeFromLoadedItems(items);

        return userListMapper.userListToDetailedResponseDto(savedList, items, watchedPercentage, false,
                items.size(), 0L, totalRuntimeMinutes, itemScope);
    }

    @Override
    @Transactional
    public UserListResponseDTO updateUserList(UUID userId, UUID listId, UserListPatchDTO userListPatchDTO) {
        UserList userList = findOwnedList(userId, listId);

        applyPatch(userList, userListPatchDTO);
        userList.setUpdatedAt(LocalDateTime.now());

        try {
            if (userListPatchDTO.rank() != null) {
                applyRankChange(userList, userListPatchDTO.rank());
            }
            return toResponseDto(userListRepository.saveAndFlush(userList), userId);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("List could not be reordered due to a concurrent update");
        }
    }

    private void applyRankChange(UserList userList, int newRank) {
        UUID userId = userList.getUser().getId();

        if (userList.getRank() == null) {
            long rankedCount = userListRepository.countByUserIdAndRankIsNotNull(userId);
            userList.setRank((int) rankedCount + 1);
            userListRepository.saveAndFlush(userList);
        }

        long currentCount = userListRepository.countByUserIdAndRankIsNotNull(userId);
        int oldRank = userList.getRank();

        if (newRank > currentCount) {
            throw new BadRequestException("rank cannot be greater than " + currentCount + ", the last rank among your lists");
        }

        if (newRank == oldRank) {
            return;
        }

        userList.setRank((int) currentCount + 1);
        userListRepository.saveAndFlush(userList);

        boolean movingForward = newRank < oldRank;
        int rangeStart = movingForward ? newRank : oldRank + 1;
        int rangeEnd = movingForward ? oldRank - 1 : newRank;
        int shiftDelta = movingForward ? 1 : -1;

        userListRepository.parkRanksInRange(userId, rangeStart, rangeEnd, RANK_PARK_OFFSET);
        userListRepository.settleParkedRanks(userId, RANK_PARK_OFFSET, shiftDelta);

        userList.setRank(newRank);
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
