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
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
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
    private final ContentStateResolver contentStateResolver;
    private final Clock clock;

    @Autowired
    public ContentViewerStateServiceImpl(
            DiaryEntryRepository diaryEntryRepository,
            WatchlistEntryRepository watchlistEntryRepository,
            DroppedEntryRepository droppedEntryRepository,
            UserListItemRepository userListItemRepository,
            ContentRepository contentRepository,
            Clock clock) {
        this(
                diaryEntryRepository,
                watchlistEntryRepository,
                droppedEntryRepository,
                userListItemRepository,
                contentRepository,
                new ContentStateResolver(),
                clock);
    }

    public ContentViewerStateServiceImpl(
            DiaryEntryRepository diaryEntryRepository,
            WatchlistEntryRepository watchlistEntryRepository,
            DroppedEntryRepository droppedEntryRepository,
            UserListItemRepository userListItemRepository,
            ContentRepository contentRepository,
            ContentStateResolver contentStateResolver,
            Clock clock) {
        this.diaryEntryRepository = Objects.requireNonNull(diaryEntryRepository, "diaryEntryRepository is required");
        this.watchlistEntryRepository = Objects.requireNonNull(
                watchlistEntryRepository, "watchlistEntryRepository is required");
        this.droppedEntryRepository = Objects.requireNonNull(droppedEntryRepository, "droppedEntryRepository is required");
        this.userListItemRepository = Objects.requireNonNull(
                userListItemRepository, "userListItemRepository is required");
        this.contentRepository = Objects.requireNonNull(contentRepository, "contentRepository is required");
        this.contentStateResolver = Objects.requireNonNull(
                contentStateResolver, "contentStateResolver is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public Resolution resolve(
            UUID viewerId,
            Collection<ContentCoordinate> coordinates,
            Map<ContentCoordinate, ContentSchedule> schedulesByCoordinate) {
        Objects.requireNonNull(viewerId, "viewerId is required");
        List<ContentCoordinate> requestedCoordinates = distinctCoordinates(coordinates);
        if (requestedCoordinates.isEmpty()) {
            return new Resolution(Map.of(), Map.of());
        }

        Map<ContentCoordinate, Content> contentByCoordinate = findExistingContent(requestedCoordinates);

        return resolveExistingContentStates(
                viewerId, requestedCoordinates, contentByCoordinate, schedulesByCoordinate);
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
        List<DiaryEntry> diaryEntries = diaryEntryRepository.findViewerStateEntriesByContentIdIn(
                viewerId, contentIds);
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
            statesByCoordinate.put(coordinate, new ContentViewerStateDTO(
                    watchProgress.status(),
                    latestDiaryEntry == null ? null : latestDiaryEntry.getScore(),
                    latestDiaryEntry == null ? null : latestDiaryEntry.getComment(),
                    latestDiaryEntry == null ? null : latestDiaryEntry.getWatchedDate(),
                    latestDiaryEntry == null ? null : latestDiaryEntry.getWatchNumber(),
                    latestDiaryEntry == null ? null : latestDiaryEntry.getWatchedInTheater(),
                    contentDiaryEntries.size(),
                    latestDiaryEntry == null ? null : latestDiaryEntry.getId(),
                    watchlistEntry != null,
                    watchlistEntry == null ? null : watchlistEntry.getId(),
                    droppedEntry != null,
                    droppedEntry == null ? null : droppedEntry.getId(),
                    toListMemberships(listItemsByContentId.getOrDefault(content.getId(), List.of())),
                    watchProgress.watchedEpisodeCount(),
                    watchProgress.releasedEpisodeCount()));
        }

        return new Resolution(statesByCoordinate, existingContentIdsByCoordinate);
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
        coordinates.stream()
                .filter(coordinate -> coordinate.type() == ContentType.MOVIE
                        || coordinate.type() == ContentType.SERIES)
                .collect(Collectors.groupingBy(
                        ContentCoordinate::type,
                        Collectors.mapping(ContentCoordinate::tmdbId, Collectors.filtering(
                                this::hasText, Collectors.toCollection(LinkedHashSet::new)))))
                .forEach((type, tmdbIds) -> {
                    if (!tmdbIds.isEmpty()) {
                        addMatchingContent(
                                contentByCoordinate,
                                coordinates,
                                contentRepository.findByTypeAndTmdbIdIn(type, tmdbIds));
                    }
                });

        coordinates.stream()
                .filter(coordinate -> coordinate.type() == ContentType.SEASON)
                .filter(coordinate -> hasText(coordinate.seriesTmdbId()) && coordinate.seasonNumber() != null)
                .collect(Collectors.groupingBy(
                        ContentCoordinate::seriesTmdbId,
                        Collectors.mapping(ContentCoordinate::seasonNumber, Collectors.toCollection(LinkedHashSet::new))))
                .forEach((seriesTmdbId, seasonNumbers) -> addMatchingContent(
                        contentByCoordinate,
                        coordinates,
                        contentRepository.findByTypeAndSeriesTmdbIdAndSeasonNumberIn(
                                ContentType.SEASON, seriesTmdbId, seasonNumbers)));

        coordinates.stream()
                .filter(coordinate -> coordinate.type() == ContentType.EPISODE)
                .filter(coordinate -> hasText(coordinate.seriesTmdbId())
                        && coordinate.seasonNumber() != null && coordinate.episodeNumber() != null)
                .collect(Collectors.groupingBy(
                        coordinate -> new SeriesSeason(coordinate.seriesTmdbId(), coordinate.seasonNumber()),
                        Collectors.mapping(ContentCoordinate::episodeNumber,
                                Collectors.toCollection(LinkedHashSet::new))))
                .forEach((seriesSeason, episodeNumbers) -> addMatchingContent(
                        contentByCoordinate,
                        coordinates,
                        contentRepository.findByTypeAndSeriesTmdbIdAndSeasonNumberAndEpisodeNumberIn(
                                ContentType.EPISODE,
                                seriesSeason.seriesTmdbId(),
                                seriesSeason.seasonNumber(),
                                episodeNumbers)));

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
        Set<WatchedEpisodeCoordinate> result = new LinkedHashSet<>();
        Set<String> seriesIds = requestedCoordinates.stream()
                .filter(coordinate -> coordinate.type() == ContentType.SERIES)
                .map(ContentCoordinate::tmdbId)
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!seriesIds.isEmpty()) {
            addWatchedEpisodeCoordinates(result,
                    diaryEntryRepository.findWatchedEpisodeCoordinatesByUserIdAndSeriesTmdbIdIn(viewerId, seriesIds));
        }

        requestedCoordinates.stream()
                .filter(coordinate -> coordinate.type() == ContentType.SEASON)
                .filter(coordinate -> hasText(coordinate.seriesTmdbId()) && coordinate.seasonNumber() != null)
                .filter(contentByCoordinate::containsKey)
                .forEach(coordinate -> addWatchedEpisodeCoordinates(result,
                        diaryEntryRepository.findWatchedEpisodeCoordinatesByUserIdAndSeriesTmdbIdAndSeasonNumber(
                                viewerId, coordinate.seriesTmdbId(), coordinate.seasonNumber())));
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

    private record SeriesSeason(String seriesTmdbId, Integer seasonNumber) {
    }
}
