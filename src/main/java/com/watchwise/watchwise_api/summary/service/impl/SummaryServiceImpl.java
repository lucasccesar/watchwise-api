package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.service.DiaryEntryService;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.summary.dto.RatingCountDTO;
import com.watchwise.watchwise_api.summary.dto.RecentActivityItemDTO;
import com.watchwise.watchwise_api.summary.dto.RecentActivityStatus;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.WatchTimeDTO;
import com.watchwise.watchwise_api.summary.service.SummaryService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class SummaryServiceImpl implements SummaryService {

    private static final int RECENT_EPISODES_LIMIT = 4;
    private static final int RECENT_REVIEWS_LIMIT = 5;
    private static final int RECENT_ACTIVITY_LIMIT = 6;
    private static final int WATCH_TIME_WINDOW_DAYS = 30;
    private static final Set<ContentType> ALLOWED_SUMMARY_TYPES = Set.of(ContentType.MOVIE, ContentType.SERIES);

    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final DiaryEntryService diaryEntryService;
    private final DroppedEntryRepository droppedEntryRepository;
    private final ContentMapper contentMapper;

    @Override
    public SummaryResponseDTO getSummary(UUID viewerId, UUID userId, ContentType type) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        assertCanViewSummary(viewerId, userId, target);

        if (type == null || !ALLOWED_SUMMARY_TYPES.contains(type)) {
            throw new BadRequestException("type must be one of: MOVIE, SERIES");
        }

        ContentType watchedContentType = type == ContentType.MOVIE ? ContentType.MOVIE : ContentType.EPISODE;

        WatchTimeDTO watchTime = computeWatchTime(userId, watchedContentType);
        List<GenreCountDTO> genreCounts = computeGenreCounts(userId, type);
        List<RatingCountDTO> ratingsDistribution = computeRatingsDistribution(userId, watchedContentType);
        List<DiaryEntryResponseDTO> recentEpisodes = type == ContentType.SERIES
                ? diaryEntryService.getDiaryEntries(viewerId, userId, null, 1, RECENT_EPISODES_LIMIT,
                        ContentType.EPISODE, null, null, null).getContent()
                : List.of();
        List<DiaryEntryResponseDTO> recentReviews = diaryEntryService.getDiaryEntries(
                viewerId, userId, null, 1, RECENT_REVIEWS_LIMIT, watchedContentType, null, null, true).getContent();
        List<RecentActivityItemDTO> recentActivity = computeRecentActivity(userId, type);

        return new SummaryResponseDTO(watchTime, genreCounts, ratingsDistribution, recentEpisodes, recentReviews, recentActivity);
    }

    private void assertCanViewSummary(UUID viewerId, UUID targetUserId, User target) {
        if (Boolean.TRUE.equals(target.getIsProfilePublic()) || viewerId.equals(targetUserId)) {
            return;
        }

        boolean viewerFollowsTarget = followerRepository
                .existsByFollowerIdAndFollowedIdAndStatus(viewerId, targetUserId, FollowStatus.ACCEPTED);

        if (!viewerFollowsTarget) {
            throw new ForbiddenException("This user profile is private");
        }
    }

    private WatchTimeDTO computeWatchTime(UUID userId, ContentType watchedContentType) {
        long totalMinutesWatched = diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentType(userId, watchedContentType);
        LocalDate windowEnd = LocalDate.now();
        LocalDate windowStart = windowEnd.minusDays(WATCH_TIME_WINDOW_DAYS);
        long minutesWatchedLast30Days = diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(
                userId, watchedContentType, windowStart, windowEnd);

        return new WatchTimeDTO(totalMinutesWatched, minutesWatchedLast30Days);
    }

    private List<GenreCountDTO> computeGenreCounts(UUID userId, ContentType type) {
        List<DiaryEntryRepository.GenreCount> rows = type == ContentType.MOVIE
                ? diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForMovies(userId)
                : diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeries(userId);

        return rows.stream().map(row -> new GenreCountDTO(row.getGenre(), row.getCount())).toList();
    }

    private List<RatingCountDTO> computeRatingsDistribution(UUID userId, ContentType watchedContentType) {
        return diaryEntryRepository.countByUserIdAndContentTypeGroupByScore(userId, watchedContentType).stream()
                .map(row -> new RatingCountDTO(row.getScore(), row.getCount()))
                .toList();
    }

    private List<RecentActivityItemDTO> computeRecentActivity(UUID userId, ContentType type) {
        PageRequest topSix = PageRequest.of(0, RECENT_ACTIVITY_LIMIT);

        Stream<RecentActivityItemDTO> completed = diaryEntryRepository
                .findTopByUserIdAndContentTypeOrderByCreatedAtDesc(userId, type, topSix).stream()
                .map(this::toCompletedActivityItem);
        Stream<RecentActivityItemDTO> dropped = droppedEntryRepository
                .findByUserIdAndTypeOrderByCreatedAtDesc(userId, type, topSix).stream()
                .map(this::toDroppedActivityItem);

        return Stream.concat(completed, dropped)
                .sorted(Comparator.comparing(RecentActivityItemDTO::activityDate).reversed())
                .limit(RECENT_ACTIVITY_LIMIT)
                .toList();
    }

    private RecentActivityItemDTO toCompletedActivityItem(DiaryEntry entry) {
        return new RecentActivityItemDTO(
                contentMapper.contentToContentRefDto(entry.getContent()),
                RecentActivityStatus.COMPLETED,
                entry.getComment(),
                entry.getCreatedAt());
    }

    private RecentActivityItemDTO toDroppedActivityItem(DroppedEntry entry) {
        return new RecentActivityItemDTO(
                contentMapper.contentToContentRefDto(entry.getContent()),
                RecentActivityStatus.DROPPED,
                entry.getComment(),
                entry.getCreatedAt());
    }

}
