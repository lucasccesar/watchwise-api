package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPage;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPageMode;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPageRequest;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemPageReader;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import com.watchwise.watchwise_api.userlist.service.UserListItemsWithState;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

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

@Service
@RequiredArgsConstructor
public class UserListItemPageReaderImpl implements UserListItemPageReader {

    private static final Set<String> ITEM_SORT_FIELDS = Set.of(
            "position", "dateAdded", "duration", "episodeAvgRating", "globalEpisodeAvgRating", "contentAvgRating");

    private final UserListRepository userListRepository;
    private final FollowerRepository followerRepository;
    private final UserListItemService userListItemService;
    private final LikeService likeService;
    private final CommentRepository commentRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final ContentRepository contentRepository;
    private final PageRequestFactory pageRequestFactory;

    @Override
    public UserListItemPage read(UserListItemPageRequest request) {
        UserList userList = userListRepository.findById(request.listId())
                .orElseThrow(() -> new NotFoundException("List not found"));
        assertListIsVisibleTo(request.viewerId(), userList);

        if (request.sortBy() != null && !ITEM_SORT_FIELDS.contains(request.sortBy())) {
            throw new BadRequestException(
                    "sortBy must be one of: position, dateAdded, duration, episodeAvgRating, globalEpisodeAvgRating, contentAvgRating");
        }
        assertValidSortDirection(request.sortDirection());

        boolean canViewOwnerRatings = canViewOwnerRatings(request.viewerId(), userList.getUser());
        if ("episodeAvgRating".equals(request.sortBy()) && !canViewOwnerRatings) {
            throw new ForbiddenException("The list owner's episode ratings are private");
        }
        if (request.page() != null && request.page() <= 0) {
            throw new BadRequestException("Page number must be greater than 0");
        }

        UserListItemsWithState itemsWithState = userListItemService.getItemsWithState(request.viewerId(), request.listId());
        List<UserListItemResponseDTO> allItems = itemsWithState.items();
        UserListItemScope itemScope = userListItemService.getItemScope(request.listId());
        if (request.watchStatus() != null && itemScope == UserListItemScope.LIST) {
            throw new BadRequestException("watchStatus is only available for content lists");
        }

        List<UserListItemResponseDTO> visibleItems = request.mode() == UserListItemPageMode.VISUAL
                ? allItems.stream()
                        .filter(item -> item.content() != null || item.childList() != null)
                        .toList()
                : allItems;
        List<UserListItemResponseDTO> filtered = filterAndSortItems(
                visibleItems, request, userList.getUser().getId(), canViewOwnerRatings);
        int maxPageSize = itemScope == UserListItemScope.EPISODE ? 24 : 30;
        PageRequest pageRequest = pageRequestFactory.build(request.page(), request.size(), maxPageSize);
        long offset = pageRequest.getOffset();
        int fromIndex = offset >= filtered.size() ? filtered.size() : (int) offset;
        int toIndex = Math.min(fromIndex + pageRequest.getPageSize(), filtered.size());
        int totalPages = filtered.isEmpty()
                ? 0
                : (int) Math.ceil((double) filtered.size() / pageRequest.getPageSize());
        boolean likedByMe = likeService.getLikedListIds(request.viewerId(), List.of(request.listId()))
                .contains(request.listId());

        return new UserListItemPage(
                userList,
                filtered.subList(fromIndex, toIndex),
                itemsWithState.watchedPercentage(),
                userList.getLikesCount(),
                likedByMe,
                pageRequest.getPageNumber() + 1,
                pageRequest.getPageSize(),
                filtered.size(),
                totalPages,
                pageRequest.getPageNumber() + 1 < totalPages,
                allItems.size(),
                commentRepository.countVisibleListComments(request.viewerId(), List.of(request.listId())).stream()
                        .mapToLong(CommentRepository.ListCommentCount::getCount).sum(),
                userListItemService.getTotalRuntimeMinutes(request.listId()),
                userList.getRank(),
                itemScope);
    }

    private List<UserListItemResponseDTO> filterAndSortItems(
            List<UserListItemResponseDTO> items, UserListItemPageRequest request, UUID ownerId,
            boolean includeOwnerRatings) {
        List<UserListItemResponseDTO> filtered = items.stream()
                .filter(item -> request.type() == null
                        || item.content() != null && item.content().type() == request.type())
                .toList();
        if (request.genre() != null) {
            Map<String, List<String>> seriesGenres = loadSeriesGenres(filtered, request.genre());
            filtered = filtered.stream()
                    .filter(item -> matchesGenre(item, request.genre(), seriesGenres))
                    .toList();
        }

        if (request.watchStatus() != null) {
            filtered = filtered.stream()
                    .filter(item -> resolvedWatchStatus(item) == request.watchStatus())
                    .toList();
        }

        filtered = enrichRatingAverages(filtered, ownerId, includeOwnerRatings);
        if (request.sortBy() == null) {
            return filtered;
        }

        Comparator<UserListItemResponseDTO> comparator = switch (request.sortBy()) {
            case "episodeAvgRating" -> ratingComparator(UserListItemResponseDTO::episodeAverageRating, request.sortDirection());
            case "globalEpisodeAvgRating" -> ratingComparator(
                    UserListItemResponseDTO::globalEpisodeAverageRating, request.sortDirection());
            case "contentAvgRating" -> ratingComparator(UserListItemResponseDTO::contentAverageRating, request.sortDirection());
            case "dateAdded" -> Comparator.comparing(UserListItemResponseDTO::createdAt);
            case "duration" -> Comparator.comparing(item -> durationMinutes(item.content()));
            default -> Comparator.comparing(
                    UserListItemResponseDTO::position, Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if ("desc".equals(request.sortDirection()) && !isRatingSort(request.sortBy())) {
            comparator = comparator.reversed();
        }
        return filtered.stream().sorted(comparator).toList();
    }

    private Map<String, List<String>> loadSeriesGenres(List<UserListItemResponseDTO> items, String genre) {
        Set<String> seriesTmdbIds = items.stream()
                .map(UserListItemResponseDTO::content)
                .filter(Objects::nonNull)
                .filter(content -> content.type() == ContentType.SEASON || content.type() == ContentType.EPISODE)
                .map(ContentRefDTO::seriesTmdbId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (seriesTmdbIds.isEmpty()) {
            return Map.of();
        }
        return contentRepository.findByTypeAndTmdbIdIn(ContentType.SERIES, seriesTmdbIds).stream()
                .filter(series -> series.getTmdbId() != null && series.getGenres() != null
                        && series.getGenres().contains(genre))
                .collect(Collectors.toMap(Content::getTmdbId, Content::getGenres, (first, ignored) -> first));
    }

    private boolean matchesGenre(
            UserListItemResponseDTO item, String genre, Map<String, List<String>> seriesGenres) {
        ContentRefDTO content = item.content();
        if (content == null) {
            return false;
        }
        if (content.type() == ContentType.MOVIE || content.type() == ContentType.SERIES) {
            return content.genres() != null && content.genres().contains(genre);
        }
        List<String> genres = seriesGenres.get(content.seriesTmdbId());
        return genres != null && genres.contains(genre);
    }

    private WatchStatus resolvedWatchStatus(UserListItemResponseDTO item) {
        return item.contentState() == null || item.contentState().watchStatus() == null
                ? WatchStatus.UNKNOWN
                : item.contentState().watchStatus();
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
                List<DiaryEntry> ownerEntries = diaryEntryRepository
                        .findScoredEpisodeEntriesByUserIdAndSeriesTmdbIdIn(ownerId, seriesTmdbIds);
                ownerEpisodeAverages = computeEpisodeAverageRatings(ownerEntries, items);
            }
            publicEpisodeAverages = computePublicEpisodeAverageRatings(
                    diaryEntryRepository.findPublicEpisodeRatingAggregatesBySeriesTmdbIdIn(seriesTmdbIds), items);
        }

        Set<UUID> contentIds = items.stream()
                .map(UserListItemResponseDTO::content)
                .filter(Objects::nonNull)
                .map(ContentRefDTO::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Double> contentAverages = new HashMap<>();
        if (!contentIds.isEmpty()) {
            for (DiaryEntryRepository.ContentStats stats : diaryEntryRepository.findContentStatsByContentIdIn(contentIds)) {
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

    private Map<UUID, Double> computeEpisodeAverageRatings(List<DiaryEntry> entries, List<UserListItemResponseDTO> items) {
        Map<String, List<Integer>> scoresBySeries = new HashMap<>();
        Map<String, List<Integer>> scoresBySeason = new HashMap<>();
        Map<String, List<Integer>> scoresByEpisode = new HashMap<>();
        for (DiaryEntry entry : entries) {
            String seriesId = entry.getContent().getSeriesTmdbId();
            Integer seasonNumber = entry.getContent().getSeasonNumber();
            Integer episodeNumber = entry.getContent().getEpisodeNumber();
            String seasonKey = seriesId + "|" + seasonNumber;
            scoresBySeries.computeIfAbsent(seriesId, ignored -> new ArrayList<>()).add(entry.getScore());
            scoresBySeason.computeIfAbsent(seasonKey, ignored -> new ArrayList<>()).add(entry.getScore());
            scoresByEpisode.computeIfAbsent(seasonKey + "|" + episodeNumber, ignored -> new ArrayList<>())
                    .add(entry.getScore());
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
                result.put(item.id(), scores.stream().mapToInt(Integer::intValue).average().orElseThrow());
            }
        }
        return result;
    }

    private Map<UUID, Double> computePublicEpisodeAverageRatings(
            List<DiaryEntryRepository.PublicEpisodeRatingAggregate> aggregates, List<UserListItemResponseDTO> items) {
        Map<String, EpisodeRatingTotals> totalsBySeries = new HashMap<>();
        Map<String, EpisodeRatingTotals> totalsBySeason = new HashMap<>();
        Map<String, EpisodeRatingTotals> totalsByEpisode = new HashMap<>();
        for (DiaryEntryRepository.PublicEpisodeRatingAggregate aggregate : aggregates) {
            String seriesId = aggregate.getSeriesTmdbId();
            String seasonKey = seriesId + "|" + aggregate.getSeasonNumber();
            String episodeKey = seasonKey + "|" + aggregate.getEpisodeNumber();
            EpisodeRatingTotals totals = new EpisodeRatingTotals(aggregate.getScoreSum(), aggregate.getScoreCount());
            totalsBySeries.merge(seriesId, totals, EpisodeRatingTotals::plus);
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

    private Comparator<UserListItemResponseDTO> ratingComparator(
            Function<UserListItemResponseDTO, Double> rating, String direction) {
        Comparator<Double> values = "desc".equals(direction)
                ? Comparator.<Double>naturalOrder().reversed()
                : Comparator.naturalOrder();
        return Comparator.comparing(rating, Comparator.nullsLast(values));
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
        return followerRepository.existsByFollowerIdAndFollowedIdAndStatus(
                viewerId, targetUserId, FollowStatus.ACCEPTED);
    }

    private void assertValidSortDirection(String direction) {
        if (direction != null && !direction.equals("asc") && !direction.equals("desc")) {
            throw new BadRequestException("sortDirection must be one of: asc, desc");
        }
    }

    private record EpisodeRatingTotals(long scoreSum, long scoreCount) {
        EpisodeRatingTotals plus(EpisodeRatingTotals other) {
            return new EpisodeRatingTotals(scoreSum + other.scoreSum, scoreCount + other.scoreCount);
        }

        double average() {
            return (double) scoreSum / scoreCount;
        }
    }
}
