package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.summary.dto.ProfileDiaryPreviewDTO;
import com.watchwise.watchwise_api.summary.dto.ProfileDiaryPreviewSource;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
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
    private final ContentReviewRepository contentReviewRepository;

    @Override
    @Transactional(readOnly = true)
    public Snapshot read(UUID userId, ContentType type) {
        ContentType watchedContentType = type == ContentType.MOVIE ? ContentType.MOVIE : ContentType.EPISODE;
        ContentType ratingContentType = type == ContentType.MOVIE ? ContentType.MOVIE : ContentType.SERIES;
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
                queryRepository.countLatestScoresByUserIdAndContentType(userId, ratingContentType.name()));

        List<DiaryEntry> recentEpisodeEntries = type == ContentType.SERIES
                ? queryRepository.findRecentEpisodes(userId, PageRequest.of(0, RECENT_EPISODES_LIMIT))
                : List.of();
        List<ContentType> reviewTypes = type == ContentType.MOVIE
                ? List.of(ContentType.MOVIE)
                : List.of(ContentType.EPISODE);
        List<DiaryEntry> recentReviewEntries = queryRepository.findRecentReviews(
                userId, reviewTypes, PageRequest.of(0, RECENT_REVIEWS_LIMIT));
        List<ContentReview> recentContentReviews = type == ContentType.SERIES
                ? contentReviewRepository.findRecentWrittenReviewsByUserIdAndContentTypeIn(
                        userId, List.of(ContentType.SERIES.name(), ContentType.SEASON.name()),
                        PageRequest.of(0, RECENT_REVIEWS_LIMIT))
                : List.of();
        List<DiaryEntry> allPreviewEntries = Stream.concat(
                        Optional.ofNullable(recentEpisodeEntries).orElse(List.of()).stream(),
                        Optional.ofNullable(recentReviewEntries).orElse(List.of()).stream())
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(DiaryEntry::getId, Function.identity(), (first, ignored) -> first))
                .values().stream().toList();
        Map<UUID, List<UserPreviewDTO>> previewCompanions = loadCompanions(allPreviewEntries);
        List<ProfileDiaryPreviewDTO> recentEpisodes = toPreviews(recentEpisodeEntries, previewCompanions);
        List<ProfileDiaryPreviewDTO> recentReviews = mergeRecentReviews(
                recentReviewEntries, recentContentReviews, previewCompanions);

        List<DiaryEntry> completedEntries = type == ContentType.MOVIE
                ? queryRepository.findRecentTopLevelEntries(userId, ContentType.MOVIE,
                        PageRequest.of(0, RECENT_ACTIVITY_LIMIT))
                : List.of();
        List<ProfileSummaryQueryRepository.SeriesCompletionActivity> seriesCompletions = type == ContentType.SERIES
                ? queryRepository.findRecentSeriesCompletionActivities(userId, PageRequest.of(0, RECENT_ACTIVITY_LIMIT))
                : List.of();
        List<DroppedEntry> droppedEntries = droppedEntryRepository
                .findByUserIdAndTypeOrderByCreatedAtDesc(userId, type, PageRequest.of(0, RECENT_ACTIVITY_LIMIT))
                .getContent();
        List<RecentActivityItemDTO> recentActivity = toRecentActivity(
                userId, type, completedEntries, seriesCompletions, droppedEntries);

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
            List<DiaryEntry> entries, Map<UUID, List<UserPreviewDTO>> companions) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
                .map(entry -> new ProfileDiaryPreviewDTO(
                        entry.getId(),
                        ProfileDiaryPreviewSource.DIARY_ENTRY,
                        contentMapper.contentToContentRefDto(entry.getContent()),
                        entry.getScore(),
                        entry.getWatchedDate(),
                        entry.getCreatedAt() == null ? entry.getWatchedDate() : entry.getCreatedAt().toLocalDate(),
                        entry.getWatchNumber(),
                        null,
                        companions.getOrDefault(entry.getId(), List.of()),
                        null))
                .toList();
    }

    private List<ProfileDiaryPreviewDTO> mergeRecentReviews(
            List<DiaryEntry> diaryReviews,
            List<ContentReview> contentReviews,
            Map<UUID, List<UserPreviewDTO>> companions) {
        List<ReviewPreviewCandidate> candidates = new ArrayList<>();
        for (DiaryEntry entry : Optional.ofNullable(diaryReviews).orElse(List.of())) {
            ProfileDiaryPreviewDTO preview = toPreviews(List.of(entry), companions).getFirst();
            Instant orderAt = entry.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
            candidates.add(new ReviewPreviewCandidate(preview, orderAt));
        }

        List<ContentReview> writtenReviews = Optional.ofNullable(contentReviews).orElse(List.of());
        if (!writtenReviews.isEmpty()) {
            Map<UUID, Content> contentsById = contentRepository.findAllById(
                            writtenReviews.stream().map(ContentReview::getContentId).distinct().toList()).stream()
                    .collect(Collectors.toMap(Content::getId, Function.identity()));
            for (ContentReview review : writtenReviews) {
                Content content = contentsById.get(review.getContentId());
                if (content == null) {
                    continue;
                }
                Instant orderAt = reviewOrderAt(review);
                LocalDate activityDate = review.getSourcePublishedAt() == null
                        && review.getSourcePublishedOn() != null
                        ? review.getSourcePublishedOn()
                        : orderAt.atZone(ZoneId.systemDefault()).toLocalDate();
                ProfileDiaryPreviewDTO preview = new ProfileDiaryPreviewDTO(
                        review.getId(),
                        ProfileDiaryPreviewSource.CONTENT_REVIEW,
                        contentMapper.contentToContentRefDto(content),
                        review.getScoreAtPublication(),
                        null,
                        activityDate,
                        review.getWatchNumber(),
                        null,
                        List.of(),
                        null);
                candidates.add(new ReviewPreviewCandidate(preview, orderAt));
            }
        }

        return candidates.stream()
                .sorted(Comparator.comparing(ReviewPreviewCandidate::orderAt).reversed()
                        .thenComparing(candidate -> candidate.preview().id(), Comparator.reverseOrder()))
                .limit(RECENT_REVIEWS_LIMIT)
                .map(ReviewPreviewCandidate::preview)
                .toList();
    }

    private Instant reviewOrderAt(ContentReview review) {
        if (review.getSourcePublishedAt() != null) {
            return review.getSourcePublishedAt();
        }
        if (review.getSourcePublishedOn() != null) {
            return review.getSourcePublishedOn().atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return review.getCreatedAt();
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
            UUID userId,
            ContentType type,
            List<DiaryEntry> completedEntries,
            List<ProfileSummaryQueryRepository.SeriesCompletionActivity> seriesCompletions,
            List<DroppedEntry> droppedEntries) {
        List<UUID> seriesContentIds = seriesCompletions.stream()
                .map(ProfileSummaryQueryRepository.SeriesCompletionActivity::getContentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, Content> seriesContentById = seriesContentIds.isEmpty()
                ? Map.of()
                : contentRepository.findAllById(seriesContentIds).stream()
                        .collect(Collectors.toMap(Content::getId, Function.identity()));

        List<UUID> contentIds = Stream.of(
                        completedEntries.stream().map(DiaryEntry::getContent),
                        seriesContentById.values().stream(),
                        droppedEntries.stream().map(DroppedEntry::getContent))
                .flatMap(Function.identity())
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

        List<ActivityCandidate> candidates = Stream.of(
                        completedEntries.stream().map(entry -> new ActivityCandidate(
                                entry.getId(), new RecentActivityItemDTO(
                                        contentMapper.contentToContentRefDto(entry.getContent()),
                                        RecentActivityStatus.COMPLETED,
                                        entry.getScore(),
                                        timesWatched.getOrDefault(entry.getContent().getId(), 0L),
                                        entry.getComment(),
                                        entry.getCreatedAt()))),
                        seriesCompletions.stream()
                                .map(activity -> {
                                    Content content = seriesContentById.get(activity.getContentId());
                                    if (content == null || activity.getOrderedAt() == null) {
                                        return null;
                                    }
                                    LocalDateTime activityDate = LocalDateTime.ofInstant(
                                            activity.getOrderedAt(), ZoneId.systemDefault());
                                    return new ActivityCandidate(activity.getProjectionId(), new RecentActivityItemDTO(
                                            contentMapper.contentToContentRefDto(content),
                                            RecentActivityStatus.COMPLETED,
                                            activity.getScore(),
                                            Optional.ofNullable(activity.getTimesWatched()).orElse(0L),
                                            activity.getComment(),
                                            activityDate));
                                })
                                .filter(Objects::nonNull),
                        droppedEntries.stream().map(entry -> new ActivityCandidate(
                                entry.getId(), new RecentActivityItemDTO(
                                        contentMapper.contentToContentRefDto(entry.getContent()),
                                        RecentActivityStatus.DROPPED,
                                        null,
                                        timesWatched.getOrDefault(entry.getContent().getId(), 0L),
                                        entry.getComment(),
                                        entry.getCreatedAt()))))
                .flatMap(Function.identity())
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

    private record ReviewPreviewCandidate(ProfileDiaryPreviewDTO preview, Instant orderAt) {
    }
}
