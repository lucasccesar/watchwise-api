package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.summary.dto.ProfileDiaryPreviewDTO;
import com.watchwise.watchwise_api.summary.dto.ProfileRewatchKind;
import com.watchwise.watchwise_api.summary.dto.RatingCountDTO;
import com.watchwise.watchwise_api.summary.dto.RecentActivityItemDTO;
import com.watchwise.watchwise_api.summary.dto.RecentActivityStatus;
import com.watchwise.watchwise_api.summary.dto.WatchTimeDTO;
import com.watchwise.watchwise_api.summary.repository.ProfileSummaryQueryRepository;
import com.watchwise.watchwise_api.summary.service.ProfileSummaryDataReader;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ProfileSummaryDataReaderImpl implements ProfileSummaryDataReader {

    private static final int RECENT_EPISODES_LIMIT = 4;
    private static final int RECENT_REVIEWS_LIMIT = 5;
    private static final int RECENT_ACTIVITY_LIMIT = 6;
    private static final int WATCH_TIME_WINDOW_DAYS = 30;

    private final ProfileSummaryQueryRepository queryRepository;
    private final DroppedEntryRepository droppedEntryRepository;
    private final ContentRepository contentRepository;
    private final ContentMapper contentMapper;
    private final WatchCompanionRepository watchCompanionRepository;
    private final UserMapper userMapper;
    private final UserContentPosterService userContentPosterService;

    @Override
    @Transactional(readOnly = true)
    public Snapshot read(UUID userId, ContentType type) {
        ContentType watchedContentType = type == ContentType.MOVIE ? ContentType.MOVIE : ContentType.EPISODE;
        LocalDate windowEnd = LocalDate.now();
        LocalDate windowStart = windowEnd.minusDays(WATCH_TIME_WINDOW_DAYS - 1L);

        WatchTimeDTO watchTime = new WatchTimeDTO(
                queryRepository.sumRuntimeMinutesByUserIdAndContentType(userId, watchedContentType),
                queryRepository.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(
                        userId, watchedContentType, windowStart, windowEnd),
                queryRepository.countByUserIdAndContentType(userId, watchedContentType),
                queryRepository.countByUserIdAndContentTypeAndWatchedDateBetween(
                        userId, watchedContentType, windowStart, windowEnd));

        List<GenreCountDTO> genreCounts = toGenreCounts(type == ContentType.MOVIE
                ? queryRepository.countDistinctMoviesByGenre(userId)
                : queryRepository.countDistinctEpisodesByGenre(userId));
        List<RatingCountDTO> ratingsDistribution = toRatingCounts(
                queryRepository.countLatestScoresByUserIdAndContentType(userId, watchedContentType.name()));

        List<DiaryEntry> recentEpisodeEntries = type == ContentType.SERIES
                ? queryRepository.findRecentEpisodes(userId, PageRequest.of(0, RECENT_EPISODES_LIMIT))
                : List.of();
        List<ContentType> reviewTypes = type == ContentType.MOVIE
                ? List.of(ContentType.MOVIE)
                : List.of(ContentType.SERIES, ContentType.SEASON, ContentType.EPISODE);
        List<DiaryEntry> recentReviewEntries = queryRepository.findRecentReviews(
                userId, reviewTypes, PageRequest.of(0, RECENT_REVIEWS_LIMIT));
        List<DiaryEntry> allPreviewEntries = Stream.concat(
                        Optional.ofNullable(recentEpisodeEntries).orElse(List.of()).stream(),
                        Optional.ofNullable(recentReviewEntries).orElse(List.of()).stream())
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(DiaryEntry::getId, Function.identity(), (first, ignored) -> first))
                .values().stream().toList();
        Map<UUID, String> previewPosters = loadPosters(userId, allPreviewEntries);
        Map<UUID, List<UserPreviewDTO>> previewCompanions = loadCompanions(allPreviewEntries);
        List<ProfileDiaryPreviewDTO> recentEpisodes = toPreviews(recentEpisodeEntries, previewPosters, previewCompanions);
        List<ProfileDiaryPreviewDTO> recentReviews = toPreviews(recentReviewEntries, previewPosters, previewCompanions);

        List<DiaryEntry> completedEntries = queryRepository.findRecentTopLevelEntries(
                userId, type, PageRequest.of(0, RECENT_ACTIVITY_LIMIT));
        List<DroppedEntry> droppedEntries = droppedEntryRepository
                .findByUserIdAndTypeOrderByCreatedAtDesc(userId, type, PageRequest.of(0, RECENT_ACTIVITY_LIMIT))
                .getContent();
        List<RecentActivityItemDTO> recentActivity = toRecentActivity(userId, type, completedEntries, droppedEntries);

        RewatchData rewatch = readRewatch(userId, type);
        LongestWatchData longestWatch = readLongestWatch(userId, type);

        return new Snapshot(watchTime, genreCounts, ratingsDistribution, recentEpisodes, recentReviews,
                recentActivity, rewatch, longestWatch);
    }

    private List<GenreCountDTO> toGenreCounts(List<ProfileSummaryQueryRepository.GenreCount> rows) {
        return Optional.ofNullable(rows).orElse(List.of()).stream()
                .map(row -> new GenreCountDTO(row.getGenre(), row.getCount()))
                .toList();
    }

    private List<RatingCountDTO> toRatingCounts(List<ProfileSummaryQueryRepository.ScoreCount> rows) {
        return Optional.ofNullable(rows).orElse(List.of()).stream()
                .map(row -> new RatingCountDTO(row.getScore(), row.getCount()))
                .toList();
    }

    private List<ProfileDiaryPreviewDTO> toPreviews(
            List<DiaryEntry> entries, Map<UUID, String> customPosters, Map<UUID, List<UserPreviewDTO>> companions) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
                .map(entry -> new ProfileDiaryPreviewDTO(
                        entry.getId(),
                        contentMapper.contentToContentRefDto(entry.getContent()),
                        entry.getScore(),
                        entry.getWatchedDate(),
                        entry.getWatchNumber(),
                        customPosters.get(entry.getContent().getId()),
                        companions.getOrDefault(entry.getId(), List.of())))
                .toList();
    }

    private Map<UUID, String> loadPosters(UUID ownerId, Collection<DiaryEntry> entries) {
        List<UUID> contentIds = entries.stream()
                .map(DiaryEntry::getContent)
                .filter(Objects::nonNull)
                .map(Content::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (contentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> posters = userContentPosterService.findByUserAndContentIds(ownerId, contentIds);
        return posters == null ? Map.of() : posters;
    }

    private Map<UUID, List<UserPreviewDTO>> loadCompanions(List<DiaryEntry> entries) {
        List<UUID> diaryEntryIds = entries.stream().map(DiaryEntry::getId).toList();
        if (diaryEntryIds.isEmpty()) {
            return Map.of();
        }
        return watchCompanionRepository.findByDiaryEntryIdIn(diaryEntryIds).stream()
                .collect(Collectors.groupingBy(
                        companion -> companion.getDiaryEntry().getId(),
                        Collectors.mapping(companion -> userMapper.userToUserPreviewDto(companion.getUser()),
                                Collectors.toList())));
    }

    private List<RecentActivityItemDTO> toRecentActivity(
            UUID userId, ContentType type, List<DiaryEntry> completedEntries, List<DroppedEntry> droppedEntries) {
        List<UUID> contentIds = Stream.concat(
                        completedEntries.stream().map(DiaryEntry::getContent),
                        droppedEntries.stream().map(DroppedEntry::getContent))
                .filter(Objects::nonNull)
                .map(Content::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<UUID, Long> timesWatched = contentIds.isEmpty()
                ? Map.of()
                : Optional.ofNullable(queryRepository.countDiaryEntriesByUserIdAndContentIdsAndContentType(
                                userId, contentIds, type.name()))
                        .orElse(List.of()).stream()
                        .collect(Collectors.toMap(ProfileSummaryQueryRepository.ContentWatchCount::getContentId,
                                ProfileSummaryQueryRepository.ContentWatchCount::getCount));

        List<ActivityCandidate> candidates = Stream.concat(
                        completedEntries.stream().map(entry -> new ActivityCandidate(
                                entry.getId(), new RecentActivityItemDTO(
                                        contentMapper.contentToContentRefDto(entry.getContent()),
                                        RecentActivityStatus.COMPLETED,
                                        entry.getScore(),
                                        timesWatched.getOrDefault(entry.getContent().getId(), 0L),
                                        entry.getComment(),
                                        entry.getCreatedAt()))),
                        droppedEntries.stream().map(entry -> new ActivityCandidate(
                                entry.getId(), new RecentActivityItemDTO(
                                        contentMapper.contentToContentRefDto(entry.getContent()),
                                        RecentActivityStatus.DROPPED,
                                        null,
                                        timesWatched.getOrDefault(entry.getContent().getId(), 0L),
                                        entry.getComment(),
                                        entry.getCreatedAt()))))
                .sorted(Comparator.comparing((ActivityCandidate candidate) -> candidate.item().activityDate())
                        .reversed()
                        .thenComparing(ActivityCandidate::sourceId, Comparator.reverseOrder()))
                .limit(RECENT_ACTIVITY_LIMIT)
                .toList();
        return candidates.stream().map(ActivityCandidate::item).toList();
    }

    private RewatchData readRewatch(UUID userId, ContentType type) {
        List<ProfileSummaryQueryRepository.ContentWatchCount> rows = Optional.ofNullable(
                        queryRepository.countDiaryEntriesGroupByContentType(userId, type.name(), PageRequest.of(0, 1)))
                .orElse(List.of());
        if (rows.isEmpty()) {
            return null;
        }
        Content content = contentRepository.findById(rows.get(0).getContentId()).orElse(null);
        if (content == null) {
            return null;
        }
        ProfileRewatchKind kind = type == ContentType.MOVIE
                ? ProfileRewatchKind.MOST_REWATCHED
                : ProfileRewatchKind.MOST_COMPLETE_REWATCHES;
        return new RewatchData(kind, content, rows.get(0).getCount());
    }

    private LongestWatchData readLongestWatch(UUID userId, ContentType type) {
        if (type == ContentType.MOVIE) {
            List<Content> movies = Optional.ofNullable(queryRepository.findLongestMovieContentByUserId(
                            userId, PageRequest.of(0, 1)))
                    .orElse(List.of());
            if (movies.isEmpty()) {
                return null;
            }
            Content movie = movies.get(0);
            return new LongestWatchData(movie, null,
                    movie.getRuntimeMinutes() == null ? 0L : movie.getRuntimeMinutes(), null);
        }

        List<ProfileSummaryQueryRepository.SeriesRuntime> series = Optional.ofNullable(
                        queryRepository.sumRuntimeMinutesByUserIdGroupBySeriesTmdbId(userId, PageRequest.of(0, 1)))
                .orElse(List.of());
        if (series.isEmpty()) {
            return null;
        }
        ProfileSummaryQueryRepository.SeriesRuntime row = series.get(0);
        return new LongestWatchData(null, row.getSeriesTmdbId(),
                Optional.ofNullable(row.getTotalMinutes()).orElse(0L), row.getEpisodeCount());
    }

    private record ActivityCandidate(UUID sourceId, RecentActivityItemDTO item) {
    }
}
