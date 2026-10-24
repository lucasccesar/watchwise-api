package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentListMembershipDTO;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentSchedule;
import com.watchwise.watchwise_api.content.service.ContentScheduleKey;
import com.watchwise.watchwise_api.content.service.ContentStateResolver;
import com.watchwise.watchwise_api.content.service.ContentViewerStateService;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diarycompletion.entity.DiaryCompletionProjection;
import com.watchwise.watchwise_api.diarycompletion.repository.DiaryCompletionProjectionRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepositoryCustom;
import com.watchwise.watchwise_api.diaryentry.repository.WatchedEpisodeCoordinate;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ContentViewerStateServiceImpl implements ContentViewerStateService {

    private static final Comparator<DiaryEntry> LATEST_DIARY_ENTRY = Comparator
            .comparing(DiaryEntry::getWatchNumber, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(DiaryEntry::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(DiaryEntry::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final DiaryEntryRepository diaryEntryRepository;
    private final WatchlistEntryRepository watchlistEntryRepository;
    private final DroppedEntryRepository droppedEntryRepository;
    private final UserListItemRepository userListItemRepository;
    private final ContentRepository contentRepository;
    private final ContentRatingRepository ratingRepository;
    private final ContentReviewRepository reviewRepository;
    private final DiaryCompletionProjectionRepository completionRepository;
    private final ContentStateResolver contentStateResolver;
    private final Clock clock;

    @Autowired
    public ContentViewerStateServiceImpl(
            DiaryEntryRepository diaryEntryRepository,
            WatchlistEntryRepository watchlistEntryRepository,
            DroppedEntryRepository droppedEntryRepository,
            UserListItemRepository userListItemRepository,
            ContentRepository contentRepository,
            ContentRatingRepository ratingRepository,
            ContentReviewRepository reviewRepository,
            DiaryCompletionProjectionRepository completionRepository,
            Clock clock) {
        this(
                diaryEntryRepository,
                watchlistEntryRepository,
                droppedEntryRepository,
                userListItemRepository,
                contentRepository,
                ratingRepository,
                reviewRepository,
                completionRepository,
                new ContentStateResolver(),
                clock);
    }

    public ContentViewerStateServiceImpl(
            DiaryEntryRepository diaryEntryRepository,
            WatchlistEntryRepository watchlistEntryRepository,
            DroppedEntryRepository droppedEntryRepository,
            UserListItemRepository userListItemRepository,
            ContentRepository contentRepository,
            ContentRatingRepository ratingRepository,
            ContentReviewRepository reviewRepository,
            DiaryCompletionProjectionRepository completionRepository,
            ContentStateResolver contentStateResolver,
            Clock clock) {
        this.diaryEntryRepository = Objects.requireNonNull(diaryEntryRepository, "diaryEntryRepository is required");
        this.watchlistEntryRepository = Objects.requireNonNull(
                watchlistEntryRepository, "watchlistEntryRepository is required");
        this.droppedEntryRepository = Objects.requireNonNull(droppedEntryRepository, "droppedEntryRepository is required");
        this.userListItemRepository = Objects.requireNonNull(
                userListItemRepository, "userListItemRepository is required");
        this.contentRepository = Objects.requireNonNull(contentRepository, "contentRepository is required");
        this.ratingRepository = Objects.requireNonNull(ratingRepository, "ratingRepository is required");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "reviewRepository is required");
        this.completionRepository = Objects.requireNonNull(completionRepository, "completionRepository is required");
        this.contentStateResolver = Objects.requireNonNull(
                contentStateResolver, "contentStateResolver is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public Resolution resolve(
            UUID viewerId,
            Collection<ContentCoordinate> coordinates,
            Map<ContentCoordinate, ContentSchedule> schedulesByCoordinate) {
        List<ContentCoordinate> requestedCoordinates = distinctCoordinates(coordinates);
        if (requestedCoordinates.isEmpty()) {
            return new Resolution(Map.of(), Map.of());
        }

        Map<ContentCoordinate, Content> contentByCoordinate = findExistingContent(requestedCoordinates);

        if (viewerId == null) {
            return resolveWithoutViewerState(requestedCoordinates, contentByCoordinate);
        }

        return resolveExistingContentStates(
                viewerId, requestedCoordinates, contentByCoordinate, schedulesByCoordinate);
    }

    private Resolution resolveWithoutViewerState(
            List<ContentCoordinate> requestedCoordinates,
            Map<ContentCoordinate, Content> contentByCoordinate) {
        Map<ContentCoordinate, ContentViewerStateDTO> emptyStates = requestedCoordinates.stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        ignored -> emptyState(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<ContentCoordinate, UUID> existingContentIdsByCoordinate = contentByCoordinate.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().getId() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().getId(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        return new Resolution(emptyStates, existingContentIdsByCoordinate);
    }

    private Resolution resolveExistingContentStates(
            UUID viewerId,
            List<ContentCoordinate> requestedCoordinates,
            Map<ContentCoordinate, Content> contentByCoordinate,
            Map<ContentCoordinate, ContentSchedule> schedulesByCoordinate) {
        Objects.requireNonNull(viewerId, "viewerId is required");

        if (contentByCoordinate.isEmpty()) {
            Map<ContentCoordinate, ContentViewerStateDTO> emptyStates = requestedCoordinates.stream()
                    .collect(Collectors.toMap(
                            Function.identity(),
                            ignored -> emptyState(),
                            (left, right) -> left,
                            LinkedHashMap::new));
            return new Resolution(emptyStates, Map.of());
        }

        Set<UUID> contentIds = contentByCoordinate.values().stream()
                .map(Content::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> diaryEventContentIds = contentByCoordinate.values().stream()
                .filter(content -> content.getType() == ContentType.MOVIE
                        || content.getType() == ContentType.EPISODE)
                .map(Content::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> opinionIds = contentByCoordinate.values().stream()
                .filter(content -> content.getType() == ContentType.SERIES
                        || content.getType() == ContentType.SEASON)
                .map(Content::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, ContentRating> ratingByContentId = opinionIds.isEmpty() ? Map.of()
                : ratingRepository.findByUserIdAndContentIdIn(viewerId, opinionIds).stream()
                        .collect(Collectors.toMap(ContentRating::getContentId, rating -> rating));
        Map<UUID, List<ContentReview>> reviewsByContentId = opinionIds.isEmpty() ? Map.of()
                : reviewRepository.findByUserIdAndContentIdInOrderByCreatedAtDesc(viewerId, opinionIds)
                        .stream().collect(Collectors.groupingBy(ContentReview::getContentId));
        Map<UUID, List<DiaryCompletionProjection>> completionsByContentId = opinionIds.isEmpty() ? Map.of()
                : completionRepository.findCurrentByUserIdAndContentIdIn(viewerId, opinionIds)
                        .stream().collect(Collectors.groupingBy(DiaryCompletionProjection::getContentId));
        List<DiaryEntry> diaryEntries = diaryEventContentIds.isEmpty()
                ? List.of()
                : diaryEntryRepository.findViewerStateEntriesByContentIdIn(viewerId, diaryEventContentIds);
        List<WatchlistEntry> watchlistEntries = watchlistEntryRepository
                .findByUserIdAndContentIdInWithContent(viewerId, contentIds);
        List<DroppedEntry> droppedEntries = droppedEntryRepository
                .findByUserIdAndContentIdInWithContent(viewerId, contentIds);
        List<UserListItem> listItems = userListItemRepository
                .findViewerMembershipsByContentIdIn(viewerId, contentIds);

        Map<UUID, List<DiaryEntry>> diaryByContentId = diaryEntries.stream()
                .filter(entry -> entry.getContent() != null && entry.getContent().getId() != null)
                .collect(Collectors.groupingBy(
                        entry -> entry.getContent().getId(), LinkedHashMap::new, Collectors.toList()));
        Set<UUID> watchedDirectContentIds = diaryEntries.stream()
                .map(DiaryEntry::getContent)
                .filter(Objects::nonNull)
                .filter(content -> content.getType() == ContentType.MOVIE
                        || content.getType() == ContentType.EPISODE)
                .map(Content::getId)
                .filter(contentIds::contains)
                .collect(Collectors.toUnmodifiableSet());
        Set<WatchedEpisodeCoordinate> watchedEpisodeCoordinates = findWatchedEpisodeCoordinates(
                viewerId, requestedCoordinates, contentByCoordinate);

        Map<UUID, WatchlistEntry> watchlistByContentId = indexWatchlistByContentId(watchlistEntries);
        Map<UUID, DroppedEntry> droppedByContentId = indexDroppedByContentId(droppedEntries);
        Map<UUID, List<UserListItem>> listItemsByContentId = listItems.stream()
                .filter(item -> item.getContent() != null && item.getContent().getId() != null)
                .collect(Collectors.groupingBy(
                        item -> item.getContent().getId(), LinkedHashMap::new, Collectors.toList()));

        Map<ContentCoordinate, ContentViewerStateDTO> statesByCoordinate = new LinkedHashMap<>();
        Map<ContentCoordinate, UUID> existingContentIdsByCoordinate = new LinkedHashMap<>();
        Map<ContentCoordinate, ContentSchedule> schedules = schedulesByCoordinate == null
                ? Map.of()
                : schedulesByCoordinate;

        for (ContentCoordinate coordinate : requestedCoordinates) {
            Content content = contentByCoordinate.get(coordinate);
            if (content == null || content.getId() == null) {
                statesByCoordinate.put(coordinate, emptyState());
                continue;
            }

            existingContentIdsByCoordinate.put(coordinate, content.getId());
            List<DiaryEntry> contentDiaryEntries = diaryByContentId.getOrDefault(content.getId(), List.of());
            DiaryEntry latestDiaryEntry = contentDiaryEntries.stream()
                    .max(LATEST_DIARY_ENTRY)
                    .orElse(null);
            ContentStateResolver.WatchProgress watchProgress = resolveWatchProgress(
                    content,
                    coordinate,
                    schedules,
                    watchedDirectContentIds,
                    watchedEpisodeCoordinates);

            WatchlistEntry watchlistEntry = watchlistByContentId.get(content.getId());
            DroppedEntry droppedEntry = droppedByContentId.get(content.getId());
            boolean opinionContent = opinionIds.contains(content.getId());
            ContentRating rating = ratingByContentId.get(content.getId());
            List<ContentReview> reviews = reviewsByContentId.getOrDefault(content.getId(), List.of()).stream()
                    .filter(review -> review.getComment() != null && !review.getComment().isBlank())
                    .sorted(Comparator.comparing(this::reviewPublicationTime).reversed()
                            .thenComparing(ContentReview::getId, Comparator.reverseOrder()))
                    .toList();
            List<DiaryCompletionProjection> completions = completionsByContentId.getOrDefault(content.getId(), List.of());
            DiaryCompletionProjection latestCompletion = completions.isEmpty() ? null : completions.getFirst();
            WatchStatus watchStatus = watchProgress.status();
            Integer watchedEpisodeCount = watchProgress.watchedEpisodeCount();
            Integer releasedEpisodeCount = watchProgress.releasedEpisodeCount();
            if (opinionContent && latestCompletion != null) {
                watchStatus = WatchStatus.WATCHED;
                if (!Objects.equals(watchedEpisodeCount, releasedEpisodeCount)) {
                    watchedEpisodeCount = null;
                    releasedEpisodeCount = null;
                }
            }
            statesByCoordinate.put(coordinate, new ContentViewerStateDTO(
                    watchStatus,
                    opinionContent ? rating == null ? null : rating.getScore()
                            : latestDiaryEntry == null ? null : latestDiaryEntry.getScore(),
                    opinionContent ? reviews.isEmpty() ? null : reviews.getFirst().getComment()
                            : latestDiaryEntry == null ? null : latestDiaryEntry.getComment(),
                    opinionContent ? latestCompletion == null ? null : latestCompletion.getCompletedOn()
                            : latestDiaryEntry == null ? null : latestDiaryEntry.getWatchedDate(),
                    opinionContent ? latestCompletion == null ? null : latestCompletion.getWatchNumber()
                            : latestDiaryEntry == null ? null : latestDiaryEntry.getWatchNumber(),
                    opinionContent ? null : latestDiaryEntry == null ? null : latestDiaryEntry.getWatchedInTheater(),
                    opinionContent ? completions.size() : contentDiaryEntries.size(),
                    opinionContent ? latestCompletion == null ? null : latestCompletion.getAnchorDiaryEntryId()
                            : latestDiaryEntry == null ? null : latestDiaryEntry.getId(),
                    watchlistEntry != null,
                    watchlistEntry == null ? null : watchlistEntry.getId(),
                    droppedEntry != null,
                    droppedEntry == null ? null : droppedEntry.getId(),
                    toListMemberships(listItemsByContentId.getOrDefault(content.getId(), List.of())),
                    watchedEpisodeCount,
                    releasedEpisodeCount,
                    opinionContent && rating != null ? rating.getVersion() : null,
                    opinionContent ? reviews.size() : 0,
                    opinionContent ? reviews.stream().map(ContentReview::getId).toList() : List.of()));
        }

        return new Resolution(statesByCoordinate, existingContentIdsByCoordinate);
    }

    private LocalDateTime reviewPublicationTime(ContentReview review) {
        Instant instant = review.getSourcePublishedAt() != null
                ? review.getSourcePublishedAt() : review.getCreatedAt();
        return review.getSourcePublishedOn() != null && review.getSourcePublishedAt() == null
                ? review.getSourcePublishedOn().atStartOfDay()
                : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private ContentStateResolver.WatchProgress resolveWatchProgress(
            Content content,
            ContentCoordinate coordinate,
            Map<ContentCoordinate, ContentSchedule> schedulesByCoordinate,
            Set<UUID> watchedDirectContentIds,
            Set<WatchedEpisodeCoordinate> watchedEpisodeCoordinates) {
        if (content.getType() == ContentType.MOVIE || content.getType() == ContentType.EPISODE) {
            boolean watched = watchedDirectContentIds.contains(content.getId());
            return new ContentStateResolver.WatchProgress(
                    watched ? WatchStatus.WATCHED : WatchStatus.UNWATCHED, null, null);
        }

        ContentSchedule schedule = schedulesByCoordinate.get(coordinate);
        if (schedule == null) {
            schedule = unknownScheduleFor(content);
        }
        return contentStateResolver.resolveWatchProgress(
                content, schedule, watchedDirectContentIds, watchedEpisodeCoordinates, clock);
    }

    private Map<ContentCoordinate, Content> findExistingContent(Collection<ContentCoordinate> coordinates) {
        Map<ContentCoordinate, Content> contentByCoordinate = new HashMap<>();
        addMatchingContent(contentByCoordinate, coordinates, contentRepository.findAllByCoordinates(coordinates));

        return contentByCoordinate;
    }

    private void addMatchingContent(
            Map<ContentCoordinate, Content> contentByCoordinate,
            Collection<ContentCoordinate> coordinates,
            Collection<Content> existingContent) {
        for (Content content : safeList(existingContent)) {
            if (content == null || content.getType() == null) {
                continue;
            }
            ContentCoordinate coordinate = ContentCoordinate.from(content);
            if (coordinates.contains(coordinate)) {
                contentByCoordinate.putIfAbsent(coordinate, content);
            }
        }
    }

    private List<ContentCoordinate> distinctCoordinates(Collection<ContentCoordinate> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) {
            return List.of();
        }
        return coordinates.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private List<ContentListMembershipDTO> toListMemberships(List<UserListItem> items) {
        return items.stream()
                .map(UserListItem::getUserList)
                .filter(Objects::nonNull)
                .filter(list -> list.getId() != null)
                .map(this::toListMembership)
                .toList();
    }

    private ContentListMembershipDTO toListMembership(UserList list) {
        return new ContentListMembershipDTO(list.getId(), list.getName(), list.getVisibility());
    }

    private Map<UUID, WatchlistEntry> indexWatchlistByContentId(Collection<WatchlistEntry> entries) {
        return safeList(entries).stream()
                .filter(Objects::nonNull)
                .filter(entry -> entry.getContent() != null
                        && entry.getContent().getId() != null
                        && entry.getType() == entry.getContent().getType())
                .collect(Collectors.toMap(
                        entry -> entry.getContent().getId(),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private Map<UUID, DroppedEntry> indexDroppedByContentId(Collection<DroppedEntry> entries) {
        return safeList(entries).stream()
                .filter(Objects::nonNull)
                .filter(entry -> entry.getContent() != null
                        && entry.getContent().getId() != null
                        && entry.getType() == entry.getContent().getType())
                .collect(Collectors.toMap(
                        entry -> entry.getContent().getId(),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private Set<WatchedEpisodeCoordinate> findWatchedEpisodeCoordinates(
            UUID viewerId,
            Collection<ContentCoordinate> requestedCoordinates,
            Map<ContentCoordinate, Content> contentByCoordinate) {
        Set<String> seriesIds = requestedCoordinates.stream()
                .filter(coordinate -> coordinate.type() == ContentType.SERIES)
                .map(ContentCoordinate::tmdbId)
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<DiaryEntryRepositoryCustom.SeriesSeasonPair> seriesSeasonPairs = requestedCoordinates.stream()
                .filter(coordinate -> coordinate.type() == ContentType.SEASON)
                .filter(coordinate -> hasText(coordinate.seriesTmdbId()) && coordinate.seasonNumber() != null)
                .filter(contentByCoordinate::containsKey)
                .map(coordinate -> new DiaryEntryRepositoryCustom.SeriesSeasonPair(
                        coordinate.seriesTmdbId(), coordinate.seasonNumber()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<WatchedEpisodeCoordinate> result = new LinkedHashSet<>();
        if (seriesIds.isEmpty() && seriesSeasonPairs.isEmpty()) {
            return Set.of();
        }
        addWatchedEpisodeCoordinates(result,
                diaryEntryRepository.findWatchedEpisodeCoordinatesByUserIdAndSeriesIdsOrSeriesSeasonPairs(
                        viewerId, seriesIds, seriesSeasonPairs));
        return Set.copyOf(result);
    }

    private void addWatchedEpisodeCoordinates(
            Set<WatchedEpisodeCoordinate> target,
            Collection<DiaryEntryRepository.WatchedEpisodeCoordinateProjection> rows) {
        for (DiaryEntryRepository.WatchedEpisodeCoordinateProjection row : safeList(rows)) {
            if (row == null || !hasText(row.getSeriesTmdbId())
                    || row.getSeasonNumber() == null || row.getSeasonNumber() < 0
                    || row.getEpisodeNumber() == null || row.getEpisodeNumber() <= 0) {
                continue;
            }
            target.add(new WatchedEpisodeCoordinate(
                    row.getSeriesTmdbId(), row.getSeasonNumber(), row.getEpisodeNumber()));
        }
    }

    private ContentViewerStateDTO emptyState() {
        return new ContentViewerStateDTO(
                WatchStatus.UNWATCHED,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                false,
                null,
                false,
                null,
                List.of(),
                null,
                null);
    }

    private ContentSchedule unknownScheduleFor(Content content) {
        ContentScheduleKey key = switch (content.getType()) {
            case MOVIE -> ContentScheduleKey.movie(content.getTmdbId());
            case SERIES -> ContentScheduleKey.series(content.getTmdbId());
            case SEASON, EPISODE -> ContentScheduleKey.season(
                    content.getSeriesTmdbId(), content.getSeasonNumber());
        };
        return new ContentSchedule(key, null, null, List.of(), false, false);
    }

    private <T> List<T> safeList(Collection<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
