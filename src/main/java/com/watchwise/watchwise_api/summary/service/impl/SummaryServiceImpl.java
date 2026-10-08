package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.mapper.DiaryEntryMapper;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.diaryentry.service.DiaryEntryService;
import com.watchwise.watchwise_api.feed.dto.FeedItemDTO;
import com.watchwise.watchwise_api.feed.service.FeedService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.summary.dto.AllTimeStatsResponseDTO;
import com.watchwise.watchwise_api.summary.dto.AllTimeEditionStatsDTO;
import com.watchwise.watchwise_api.summary.dto.ContentWatchCountDTO;
import com.watchwise.watchwise_api.summary.dto.CountryCountDTO;
import com.watchwise.watchwise_api.summary.dto.DailyMinutesDTO;
import com.watchwise.watchwise_api.summary.dto.DayOfWeekCountDTO;
import com.watchwise.watchwise_api.summary.dto.DecadeCountDTO;
import com.watchwise.watchwise_api.summary.dto.DailyWatchCountDTO;
import com.watchwise.watchwise_api.summary.dto.EpisodeRatingsGridResponseDTO;
import com.watchwise.watchwise_api.summary.dto.EpisodeScoreDTO;
import com.watchwise.watchwise_api.summary.dto.EpisodeRatingsMapItemDTO;
import com.watchwise.watchwise_api.summary.dto.EpisodeRatingsMapResponseDTO;
import com.watchwise.watchwise_api.summary.dto.HomeSummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.HomeRecentlyWatchedDTO;
import com.watchwise.watchwise_api.summary.dto.HomeSocialActivityDTO;
import com.watchwise.watchwise_api.summary.dto.HomeViewerDTO;
import com.watchwise.watchwise_api.summary.dto.HomeContentReferenceDTO;
import com.watchwise.watchwise_api.summary.dto.LongestWatchedItemDTO;
import com.watchwise.watchwise_api.summary.dto.MonthCountDTO;
import com.watchwise.watchwise_api.summary.dto.MonthInReviewResponseDTO;
import com.watchwise.watchwise_api.summary.dto.RatingCountDTO;
import com.watchwise.watchwise_api.summary.dto.SeriesWatchTimeDTO;
import com.watchwise.watchwise_api.summary.dto.SeriesInProgressPreviewDTO;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.WatchCompanionCountDTO;
import com.watchwise.watchwise_api.summary.dto.WatchTimeDTO;
import com.watchwise.watchwise_api.summary.dto.YearCountDTO;
import com.watchwise.watchwise_api.summary.dto.YearInReviewResponseDTO;
import com.watchwise.watchwise_api.summary.service.ProfileSummaryReader;
import com.watchwise.watchwise_api.summary.service.AllTimeStatsReader;
import com.watchwise.watchwise_api.summary.service.SummaryService;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataRefreshService;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class SummaryServiceImpl implements SummaryService {

    private static final int WATCH_TIME_WINDOW_DAYS = 30;
    private static final int HOME_NEXT_EPISODES_LIMIT = 4;
    private static final int HOME_RECENTLY_WATCHED_LIMIT = 4;
    private static final Set<ContentType> HOME_WATCHED_CONTENT_TYPES = Set.of(ContentType.MOVIE, ContentType.EPISODE);
    private static final Set<ContentCardFieldSet> HOME_CARD_FIELDS = Set.of(
            ContentCardFieldSet.BASIC_METADATA, ContentCardFieldSet.SOCIAL_METADATA);
    private static final int MONTH_TOP_LIMIT = 6;
    private static final int YEAR_TOP_LIMIT = 10;
    private static final int ALL_TIME_TOP_LIMIT = 10;
    private static final int TOP_SERIES_LIMIT = 3;
    private static final int TOP_LONGEST_MOVIES_LIMIT = 3;
    private static final int TOP_COMPANIONS_LIMIT = 3;
    private static final int YEAR_LONGEST_LIMIT = 10;
    private static final int SINGLE_WATCHED_ENTRY_LIMIT = 1;
    private static final double AVERAGE_DAYS_PER_MONTH = 30.44;
    private static final Set<ContentType> ALLOWED_SUMMARY_TYPES = Set.of(ContentType.MOVIE, ContentType.SERIES);

    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final DiaryEntryService diaryEntryService;
    private final ContentRepository contentRepository;
    private final ContentMapper contentMapper;
    private final DiaryEntryMapper diaryEntryMapper;
    private final Top5EntryRepository top5EntryRepository;
    private final WatchCompanionRepository watchCompanionRepository;
    private final UserMapper userMapper;
    private final SeriesProgressMetadataRefreshService seriesProgressMetadataRefreshService;
    private final UserContentPosterService userContentPosterService;
    private final NotificationRepository notificationRepository;
    private final FeedService feedService;
    private final HomeNextEpisodeAssembler homeNextEpisodeAssembler;
    private final ContentCardAssembler contentCardAssembler;
    private final ProfileSummaryReader profileSummaryReader;
    private final AllTimeStatsReader allTimeStatsReader;

    @Override
    public SummaryResponseDTO getSummary(UUID viewerId, UUID userId, ContentType type) {
        return profileSummaryReader.read(viewerId, userId, type);
    }

    @Override
    public HomeSummaryResponseDTO getHomeSummary(UUID viewerId, UUID userId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewSummary(viewerId, userId, target);

        DiaryEntryRepository.HomeWatchAggregate aggregate = diaryEntryRepository.findHomeWatchAggregate(userId);
        long totalMinutesWatchedMovies = aggregate.getTotalMinutesWatchedMovies();
        long totalMinutesWatchedEpisodes = aggregate.getTotalMinutesWatchedEpisodes();
        long totalMoviesWatched = aggregate.getTotalMoviesWatched();
        long totalDistinctMoviesWatched = aggregate.getTotalDistinctMoviesWatched();
        long totalEpisodesWatched = aggregate.getTotalEpisodesWatched();
        long distinctSeriesWatched = aggregate.getDistinctSeriesWatched();

        List<SeriesInProgressResponseDTO> progress;
        try {
            progress = diaryEntryService
                    .getSeriesInProgress(viewerId, userId, 1, HOME_NEXT_EPISODES_LIMIT).getContent();
        } catch (TmdbUnavailableException exception) {
            progress = List.of();
        }
        List<SeriesInProgressPreviewDTO> nextEpisodes = homeNextEpisodeAssembler.assemble(target, progress);

        LocalDate windowEnd = LocalDate.now();
        LocalDate windowStart = windowEnd.minusDays(WATCH_TIME_WINDOW_DAYS - 1L);

        List<DailyWatchCountDTO> watchCountByDayLast30Days = diaryEntryRepository
                .countByUserIdAndWatchedDateBetween(userId, windowStart, windowEnd).stream()
                .map(row -> new DailyWatchCountDTO(row.getWatchedDate(), row.getCount()))
                .toList();

        List<GenreCountDTO> genreCountsMoviesLast30Days = diaryEntryRepository
                .countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(userId, windowStart, windowEnd).stream()
                .map(row -> new GenreCountDTO(row.getGenre(), row.getCount()))
                .toList();
        List<GenreCountDTO> genreCountsEpisodesLast30Days = diaryEntryRepository
                .countEpisodeEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween(userId, windowStart, windowEnd).stream()
                .map(row -> new GenreCountDTO(row.getGenre(), row.getCount()))
                .toList();

        List<DiaryEntry> recentEntries = loadHomeRecentlyWatchedEntries(userId);
        List<FeedItemDTO> feedItems = feedService.getFeed(userId, null, 3).content();
        Map<ContentCoordinate, ContentCardDTO> cards = assembleHomeCards(userId, target, recentEntries, feedItems);
        List<HomeRecentlyWatchedDTO> recentlyWatched = toHomeRecentlyWatched(recentEntries, cards);
        List<HomeSocialActivityDTO> socialActivities = feedItems.stream()
                .map(item -> toHomeSocialActivity(item, cards))
                .toList();
        HomeViewerDTO viewer = new HomeViewerDTO(target.getName(), target.getUsername(), target.getProfilePicture());

        return new HomeSummaryResponseDTO(totalMinutesWatchedMovies, totalMinutesWatchedEpisodes, totalMoviesWatched,
                totalDistinctMoviesWatched, totalEpisodesWatched, distinctSeriesWatched, nextEpisodes,
                watchCountByDayLast30Days,
                genreCountsMoviesLast30Days, genreCountsEpisodesLast30Days, viewer,
                notificationRepository.existsByUserIdAndIsReadFalse(userId), recentlyWatched, socialActivities);
    }

    private List<DiaryEntry> loadHomeRecentlyWatchedEntries(UUID userId) {
        PageRequest topN = PageRequest.of(0, HOME_RECENTLY_WATCHED_LIMIT);
        return diaryEntryRepository.findRecentHomeEntries(userId, HOME_WATCHED_CONTENT_TYPES, topN);
    }

    private List<HomeRecentlyWatchedDTO> toHomeRecentlyWatched(
            List<DiaryEntry> entries, Map<ContentCoordinate, ContentCardDTO> cards) {
        Map<UUID, List<String>> companionPictures = watchCompanionRepository
                .findByDiaryEntryIdIn(entries.stream().map(DiaryEntry::getId).toList()).stream()
                .collect(Collectors.groupingBy(
                        companion -> companion.getDiaryEntry().getId(),
                        Collectors.mapping(companion -> companion.getUser().getProfilePicture(), Collectors.toList())));
        return entries.stream()
                .map(entry -> {
                    Content content = entry.getContent();
                    ContentCardDTO card = cards.get(ContentCoordinate.from(content));
                    return new HomeRecentlyWatchedDTO(entry.getId(), new HomeContentReferenceDTO(
                                    content.getId(), content.getTmdbId(), content.getType(),
                                    content.getSeriesTmdbId(), content.getSeasonNumber(),
                                    content.getEpisodeNumber(), content.getRuntimeMinutes()),
                            entry.getScore(), entry.getWatchedDate(),
                            card == null ? null : card.customPosterUrl(),
                            companionPictures.getOrDefault(entry.getId(), List.of()), card);
                })
                .toList();
    }

    private HomeSocialActivityDTO toHomeSocialActivity(
            FeedItemDTO item, Map<ContentCoordinate, ContentCardDTO> cards) {
        String targetLabel = item.pick() != null ? "Pick" : item.picksTemplate() != null ? item.picksTemplate().name()
                : item.top5Type() == null ? null : "Top 5 " + item.top5Type().name().toLowerCase();
        ContentCardDTO card = item.content() == null ? null : cards.get(toCoordinate(item.content()));
        return new HomeSocialActivityDTO(item.eventType(), item.id(), item.user(), item.content(), targetLabel,
                item.likesCount(), item.commentsCount(), item.createdAt(), card);
    }

    private Map<ContentCoordinate, ContentCardDTO> assembleHomeCards(
            UUID posterUserId, User target, List<DiaryEntry> recentEntries, List<FeedItemDTO> feedItems) {
        List<ContentCardSpec> specs = Stream.concat(
                        recentEntries.stream()
                                .map(DiaryEntry::getContent)
                                .filter(content -> content != null && content.getType() != null)
                                .map(this::toCardSpec),
                        feedItems.stream()
                                .map(FeedItemDTO::content)
                                .filter(content -> content != null && content.type() != null)
                                .map(content -> new ContentCardSpec(toCoordinate(content), null, null, null, null)))
                .toList();
        Map<ContentCoordinate, ContentCardDTO> cards = contentCardAssembler.assemble(
                specs,
                new ContentCardContext(target.getPreferredLanguage(), target.getPreferredRegion(), posterUserId, null),
                HOME_CARD_FIELDS);
        return cards == null ? Map.of() : cards;
    }

    private ContentCardSpec toCardSpec(Content content) {
        return new ContentCardSpec(ContentCoordinate.from(content), null, null, null, content.getRuntimeMinutes());
    }

    private ContentCoordinate toCoordinate(ContentRefDTO content) {
        return new ContentCoordinate(content.type(), content.tmdbId(), content.seriesTmdbId(),
                content.seasonNumber(), content.episodeNumber());
    }

    private List<DiaryEntryResponseDTO> computeRecentlyWatched(UUID userId) {
        PageRequest topN = PageRequest.of(0, HOME_RECENTLY_WATCHED_LIMIT);

        Stream<DiaryEntry> movies = diaryEntryRepository
                .findTopByUserIdAndContentTypeOrderByCreatedAtDesc(userId, ContentType.MOVIE, topN).stream();
        Stream<DiaryEntry> episodes = diaryEntryRepository
                .findTopByUserIdAndContentTypeOrderByCreatedAtDesc(userId, ContentType.EPISODE, topN).stream();

        List<DiaryEntry> entries = Stream.concat(movies, episodes)
                .sorted(Comparator.comparing(DiaryEntry::getCreatedAt).reversed())
                .limit(HOME_RECENTLY_WATCHED_LIMIT)
                .toList();
        return toDiaryEntryResponseDtos(entries, loadPostersForOwner(userId, entries));
    }

    @Override
    public MonthInReviewResponseDTO getMonthInReview(UUID viewerId, UUID userId, ContentType type, YearMonth month) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewSummary(viewerId, userId, target);

        if (type == null || !ALLOWED_SUMMARY_TYPES.contains(type)) {
            throw new BadRequestException("type must be one of: MOVIE, SERIES");
        }
        if (month == null) {
            throw new BadRequestException("month must be provided");
        }

        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        ContentType watchedContentType = watchedContentTypeFor(type);
        PageRequest topN = PageRequest.of(0, MONTH_TOP_LIMIT);

        List<DiaryEntry> recentWatchedEntries = diaryEntryRepository
                .findByUserIdAndContentTypeAndWatchedDateBetweenOrderByWatchedDateDesc(userId, type, start, end, topN)
                .stream().toList();

        List<DiaryEntry> topRatedRaw = diaryEntryRepository
                .findTopRatedByUserIdAndContentTypeAndWatchedDateBetween(userId, type, start, end, topN);
        List<DiaryEntry> bottomRatedRaw = diaryEntryRepository
                .findBottomRatedByUserIdAndContentTypeAndWatchedDateBetween(userId, type, start, end, topN);

        List<RatingCountDTO> ratingsDistribution = diaryEntryRepository
                .countByUserIdAndContentTypeAndWatchedDateBetweenGroupByScore(userId, watchedContentType, start, end)
                .stream().map(row -> new RatingCountDTO(row.getScore(), row.getCount())).toList();

        long watchCount = diaryEntryRepository.countByUserIdAndContentTypeAndWatchedDateBetween(userId, watchedContentType, start, end);
        long minutesWatched = diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(userId, watchedContentType, start, end);
        PageRequest singleEntry = PageRequest.of(0, SINGLE_WATCHED_ENTRY_LIMIT);
        DiaryEntry firstWatchedEntry = diaryEntryRepository
                .findEarliestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                        userId, watchedContentType, start, end, singleEntry)
                .stream().findFirst().orElse(null);
        DiaryEntry lastWatchedEntry = diaryEntryRepository
                .findLatestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                        userId, watchedContentType, start, end, singleEntry)
                .stream().findFirst().orElse(null);
        Map<UUID, String> customPosterByContentId = loadPostersForOwner(userId,
                collectDiaryEntries(recentWatchedEntries, topRatedRaw, bottomRatedRaw,
                        firstWatchedEntry == null ? List.of() : List.of(firstWatchedEntry),
                        lastWatchedEntry == null ? List.of() : List.of(lastWatchedEntry)));
        List<DiaryEntryResponseDTO> recentWatched = toDiaryEntryResponseDtos(recentWatchedEntries, customPosterByContentId);
        List<DiaryEntryResponseDTO> topRated = promoteTop5First(topRatedRaw, userId, List.of(type), customPosterByContentId);
        List<DiaryEntryResponseDTO> bottomRated = sortBottomRated(bottomRatedRaw, customPosterByContentId);
        DiaryEntryResponseDTO firstWatched = firstWatchedEntry == null
                ? null : toDiaryEntryResponseDto(firstWatchedEntry, customPosterByContentId);
        DiaryEntryResponseDTO lastWatched = lastWatchedEntry == null
                ? null : toDiaryEntryResponseDto(lastWatchedEntry, customPosterByContentId);

        List<DailyMinutesDTO> minutesPerDay = diaryEntryRepository
                .sumRuntimeMinutesByUserIdAndContentTypeGroupByWatchedDateBetween(userId, watchedContentType, start, end)
                .stream().map(row -> new DailyMinutesDTO(row.getWatchedDate(), row.getMinutes())).toList();

        List<DayOfWeekCountDTO> watchCountByDayOfWeek = (type == ContentType.MOVIE
                ? diaryEntryRepository.countByUserIdAndWatchedDateBetweenGroupByDayOfWeekForMovies(userId, start, end)
                : diaryEntryRepository.countByUserIdAndWatchedDateBetweenGroupByDayOfWeekForEpisodes(userId, start, end))
                .stream().map(row -> new DayOfWeekCountDTO(row.getDayOfWeek(), row.getCount())).toList();

        List<GenreCountDTO> genreCounts = (type == ContentType.MOVIE
                ? diaryEntryRepository.countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(userId, start, end)
                : diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(userId, start, end))
                .stream().map(row -> new GenreCountDTO(row.getGenre(), row.getCount())).toList();

        List<SeriesWatchTimeDTO> topSeriesByWatchTime = type == ContentType.SERIES
                ? diaryEntryRepository.sumRuntimeMinutesByUserIdGroupBySeriesTmdbIdAndWatchedDateBetween(
                                userId, start, end, PageRequest.of(0, TOP_SERIES_LIMIT))
                        .stream().map(row -> new SeriesWatchTimeDTO(row.getContentId(), row.getSeriesTmdbId(), row.getTotalMinutes())).toList()
                : List.of();

        List<com.watchwise.watchwise_api.content.dto.ContentRefDTO> topLongestMovies = type == ContentType.MOVIE
                ? diaryEntryRepository.findDistinctMovieContentByUserIdAndWatchedDateBetweenOrderByRuntimeDesc(
                                userId, start, end, PageRequest.of(0, TOP_LONGEST_MOVIES_LIMIT))
                        .stream().map(contentMapper::contentToContentRefDto).toList()
                : List.of();

        List<WatchCompanionCountDTO> topWatchCompanions = computeTopWatchCompanions(userId, watchedContentType, start, end);

        return new MonthInReviewResponseDTO(recentWatched, topRated, bottomRated, ratingsDistribution, watchCount,
                minutesWatched, firstWatched, lastWatched, minutesPerDay, watchCountByDayOfWeek, genreCounts,
                topSeriesByWatchTime, topLongestMovies, topWatchCompanions);
    }

    @Override
    public YearInReviewResponseDTO getYearInReview(UUID viewerId, UUID userId, ContentType type, Integer year) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewSummary(viewerId, userId, target);

        if (type == null || !ALLOWED_SUMMARY_TYPES.contains(type)) {
            throw new BadRequestException("type must be one of: MOVIE, SERIES");
        }
        if (year == null) {
            throw new BadRequestException("year must be provided");
        }

        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        ContentType watchedContentType = watchedContentTypeFor(type);
        PageRequest topN = PageRequest.of(0, YEAR_TOP_LIMIT);

        List<DiaryEntry> topRatedRaw = diaryEntryRepository
                .findTopRatedByUserIdAndContentTypeAndWatchedDateBetween(userId, type, start, end, topN);
        List<DiaryEntry> bottomRatedRaw = diaryEntryRepository
                .findBottomRatedByUserIdAndContentTypeAndWatchedDateBetween(userId, type, start, end, topN);

        List<RatingCountDTO> ratingsDistribution = diaryEntryRepository
                .countByUserIdAndContentTypeAndWatchedDateBetweenGroupByScore(userId, watchedContentType, start, end)
                .stream().map(row -> new RatingCountDTO(row.getScore(), row.getCount())).toList();

        long watchCount = diaryEntryRepository.countByUserIdAndContentTypeAndWatchedDateBetween(userId, watchedContentType, start, end);
        long minutesWatched = diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(userId, watchedContentType, start, end);

        double averageMinutesPerDay = minutesWatched / (double) start.lengthOfYear();
        double averageMinutesPerWeek = averageMinutesPerDay * 7;
        double averageMinutesPerMonth = averageMinutesPerDay * AVERAGE_DAYS_PER_MONTH;

        List<MonthCountDTO> watchCountByMonth = (type == ContentType.MOVIE
                ? diaryEntryRepository.countByUserIdAndWatchedDateBetweenGroupByMonthForMovies(userId, start, end)
                : diaryEntryRepository.countByUserIdAndWatchedDateBetweenGroupByMonthForEpisodes(userId, start, end))
                .stream().map(row -> new MonthCountDTO(row.getMonth(), row.getCount())).toList();

        List<DayOfWeekCountDTO> watchCountByDayOfWeek = (type == ContentType.MOVIE
                ? diaryEntryRepository.countByUserIdAndWatchedDateBetweenGroupByDayOfWeekForMovies(userId, start, end)
                : diaryEntryRepository.countByUserIdAndWatchedDateBetweenGroupByDayOfWeekForEpisodes(userId, start, end))
                .stream().map(row -> new DayOfWeekCountDTO(row.getDayOfWeek(), row.getCount())).toList();

        PageRequest singleEntry = PageRequest.of(0, SINGLE_WATCHED_ENTRY_LIMIT);
        DiaryEntry firstWatchedEntry = diaryEntryRepository
                .findEarliestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                        userId, watchedContentType, start, end, singleEntry)
                .stream().findFirst().orElse(null);
        DiaryEntry lastWatchedEntry = diaryEntryRepository
                .findLatestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                        userId, watchedContentType, start, end, singleEntry)
                .stream().findFirst().orElse(null);
        Map<UUID, String> customPosterByContentId = loadPostersForOwner(userId,
                collectDiaryEntries(topRatedRaw, bottomRatedRaw,
                        firstWatchedEntry == null ? List.of() : List.of(firstWatchedEntry),
                        lastWatchedEntry == null ? List.of() : List.of(lastWatchedEntry)));
        List<DiaryEntryResponseDTO> topRated = promoteTop5First(topRatedRaw, userId, List.of(type), customPosterByContentId);
        List<DiaryEntryResponseDTO> bottomRated = sortBottomRated(bottomRatedRaw, customPosterByContentId);
        DiaryEntryResponseDTO firstWatched = firstWatchedEntry == null
                ? null : toDiaryEntryResponseDto(firstWatchedEntry, customPosterByContentId);
        DiaryEntryResponseDTO lastWatched = lastWatchedEntry == null
                ? null : toDiaryEntryResponseDto(lastWatchedEntry, customPosterByContentId);

        List<LongestWatchedItemDTO> longestWatched = computeLongestWatched(userId, type, start, end);

        List<GenreCountDTO> genreCounts = (type == ContentType.MOVIE
                ? diaryEntryRepository.countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(userId, start, end)
                : diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(userId, start, end))
                .stream().map(row -> new GenreCountDTO(row.getGenre(), row.getCount())).toList();

        List<WatchCompanionCountDTO> topWatchCompanions = computeTopWatchCompanions(userId, watchedContentType, start, end);

        return new YearInReviewResponseDTO(ratingsDistribution, watchCount, minutesWatched, averageMinutesPerMonth,
                averageMinutesPerWeek, averageMinutesPerDay, watchCountByMonth, watchCountByDayOfWeek,
                firstWatched, lastWatched, longestWatched, genreCounts, topRated, bottomRated, topWatchCompanions);
    }

    @Override
    public AllTimeStatsResponseDTO getAllTimeStats(UUID viewerId, UUID userId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewSummary(viewerId, userId, target);

        long totalMoviesWatched = diaryEntryRepository.countByUserIdAndContentType(userId, ContentType.MOVIE);
        long totalEpisodesWatched = diaryEntryRepository.countByUserIdAndContentType(userId, ContentType.EPISODE);
        long totalMinutesWatchedMovies = diaryEntryRepository
                .sumRuntimeMinutesByUserIdAndContentType(userId, ContentType.MOVIE);
        long totalMinutesWatchedEpisodes = diaryEntryRepository
                .sumRuntimeMinutesByUserIdAndContentType(userId, ContentType.EPISODE);
        long totalMinutesWatched = totalMinutesWatchedMovies + totalMinutesWatchedEpisodes;
        long totalTheaterVisits = diaryEntryRepository.countByUserIdAndWatchedInTheaterTrue(userId);

        LocalDate firstWatched = diaryEntryRepository.findMinWatchedDateByUserId(userId).orElse(LocalDate.now());
        long daysSinceFirstWatched = Math.max(1, ChronoUnit.DAYS.between(firstWatched, LocalDate.now()) + 1);
        double averageMinutesPerDay = totalMinutesWatched / (double) daysSinceFirstWatched;
        double averageMinutesPerWeek = averageMinutesPerDay * 7;
        double averageMinutesPerMonth = averageMinutesPerDay * AVERAGE_DAYS_PER_MONTH;

        List<YearCountDTO> watchCountByYearMovies = diaryEntryRepository.countByUserIdGroupByYearForMovies(userId)
                .stream().map(row -> new YearCountDTO(row.getYear(), row.getCount())).toList();
        List<YearCountDTO> watchCountByYearEpisodes = diaryEntryRepository.countByUserIdGroupByYearForEpisodes(userId)
                .stream().map(row -> new YearCountDTO(row.getYear(), row.getCount())).toList();

        List<DecadeCountDTO> watchCountByDecade = diaryEntryRepository.countDistinctTitlesByDecadeAndUserId(userId)
                .stream().map(row -> new DecadeCountDTO(row.getDecade(), row.getCount())).toList();

        List<CountryCountDTO> watchCountByCountry = diaryEntryRepository.countDistinctTitlesByCountryAndUserId(userId)
                .stream().map(row -> new CountryCountDTO(row.getCountry(), row.getCount())).toList();

        List<DiaryEntryRepository.ContentWatchCount> mostLoggedRaw = diaryEntryRepository
                .countDiaryEntriesGroupByContentId(userId, PageRequest.of(0, ALL_TIME_TOP_LIMIT));
        Map<UUID, Content> contentById = contentRepository
                .findAllById(mostLoggedRaw.stream().map(DiaryEntryRepository.ContentWatchCount::getContentId).toList())
                .stream().collect(Collectors.toMap(Content::getId, c -> c));
        List<ContentWatchCountDTO> mostLoggedContent = mostLoggedRaw.stream()
                .map(row -> new ContentWatchCountDTO(
                        contentMapper.contentToContentRefDto(contentById.get(row.getContentId())), row.getCount()))
                .toList();

        List<GenreCountDTO> genreCountsMovies = diaryEntryRepository.countEntriesByGenreAndUserIdForMovies(userId)
                .stream().map(row -> new GenreCountDTO(row.getGenre(), row.getCount())).toList();
        List<GenreCountDTO> genreCountsSeries = diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeries(userId)
                .stream().map(row -> new GenreCountDTO(row.getGenre(), row.getCount())).toList();

        List<DiaryEntry> topRatedRaw = diaryEntryRepository.findTopRatedByUserId(userId, PageRequest.of(0, ALL_TIME_TOP_LIMIT));
        List<DiaryEntry> bottomRatedRaw = diaryEntryRepository.findBottomRatedByUserId(userId, PageRequest.of(0, ALL_TIME_TOP_LIMIT));
        Map<UUID, String> customPosterByContentId = loadPostersForOwner(userId,
                collectDiaryEntries(topRatedRaw, bottomRatedRaw));
        List<DiaryEntryResponseDTO> topRated = promoteTop5First(topRatedRaw, userId,
                List.of(ContentType.MOVIE, ContentType.SERIES), customPosterByContentId);
        List<DiaryEntryResponseDTO> bottomRated = sortBottomRated(bottomRatedRaw, customPosterByContentId);

        List<WatchCompanionCountDTO> topWatchCompanions = computeTopWatchCompanionsAllTime(userId);

        return new AllTimeStatsResponseDTO(totalMoviesWatched, totalEpisodesWatched, totalMinutesWatchedMovies,
                totalMinutesWatchedEpisodes, totalTheaterVisits, averageMinutesPerMonth, averageMinutesPerWeek,
                averageMinutesPerDay,
                watchCountByYearMovies, watchCountByYearEpisodes, watchCountByDecade, watchCountByCountry,
                mostLoggedContent, genreCountsMovies, genreCountsSeries, topRated, bottomRated, topWatchCompanions);
    }

    @Override
    public AllTimeEditionStatsDTO getAllTimeStatsEdition(UUID viewerId, UUID userId, ContentType type) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewSummary(viewerId, userId, target);

        if (type == null || !ALLOWED_SUMMARY_TYPES.contains(type)) {
            throw new BadRequestException("type must be one of: MOVIE, SERIES");
        }

        return allTimeStatsReader.read(userId, type);
    }

    @Override
    public EpisodeRatingsGridResponseDTO getEpisodeRatingsGrid(UUID viewerId, UUID userId, String seriesTmdbId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewSummary(viewerId, userId, target);

        if (seriesTmdbId != null) {
            seriesTmdbId = seriesTmdbId.trim();
        }
        if (StringUtils.isEmpty(seriesTmdbId)) {
            throw new BadRequestException("seriesTmdbId must be provided");
        }

        List<DiaryEntry> entries = diaryEntryRepository.findEpisodeEntriesBySeriesForUser(userId, seriesTmdbId);

        Map<List<Integer>, DiaryEntry> latestPerEpisode = new LinkedHashMap<>();
        for (DiaryEntry entry : entries) {
            List<Integer> key = List.of(entry.getContent().getSeasonNumber(), entry.getContent().getEpisodeNumber());
            DiaryEntry current = latestPerEpisode.get(key);
            if (current == null || entry.getWatchNumber() > current.getWatchNumber()) {
                latestPerEpisode.put(key, entry);
            }
        }

        List<EpisodeScoreDTO> episodes = latestPerEpisode.values().stream()
                .sorted(Comparator.comparing((DiaryEntry d) -> d.getContent().getSeasonNumber())
                        .thenComparing(d -> d.getContent().getEpisodeNumber()))
                .map(d -> new EpisodeScoreDTO(d.getContent().getSeasonNumber(), d.getContent().getEpisodeNumber(), d.getScore()))
                .toList();

        Map<String, String> customPosterBySeries = loadSeriesPosters(userId, List.of(seriesTmdbId));
        return new EpisodeRatingsGridResponseDTO(seriesTmdbId, episodes, customPosterBySeries.get(seriesTmdbId));
    }

    @Override
    public EpisodeRatingsMapResponseDTO getEpisodeRatingsMap(UUID viewerId, UUID userId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewSummary(viewerId, userId, target);

        List<DiaryEntryRepository.SeriesEpisodeCount> counts = diaryEntryRepository
                .findEpisodeSeriesCountsByUserId(userId);
        if (counts.isEmpty()) {
            return new EpisodeRatingsMapResponseDTO(List.of());
        }

        List<String> seriesTmdbIds = counts.stream()
                .map(DiaryEntryRepository.SeriesEpisodeCount::getSeriesTmdbId)
                .toList();
        Map<String, SeriesProgressMetadataRefreshService.Snapshot> snapshots =
                seriesProgressMetadataRefreshService.getSnapshotsForRead(seriesTmdbIds, LocalDate.now());
        Map<String, String> customPosterBySeries = loadSeriesPosters(userId, seriesTmdbIds);

        List<EpisodeRatingsMapItemDTO> series = counts.stream()
                .map(row -> {
                    SeriesProgressMetadataRefreshService.Snapshot snapshot = snapshots.get(row.getSeriesTmdbId());
                    Integer totalEpisodeCount = snapshot == null || snapshot.series() == null
                            ? null
                            : snapshot.series().regularReleasedEpisodeCount();
                    return new EpisodeRatingsMapItemDTO(
                            row.getSeriesTmdbId(), row.getWatchedEpisodeCount(), totalEpisodeCount,
                            customPosterBySeries.get(row.getSeriesTmdbId()));
                })
                .toList();

        return new EpisodeRatingsMapResponseDTO(series);
    }

    private Map<String, String> loadSeriesPosters(UUID userId, List<String> seriesTmdbIds) {
        if (seriesTmdbIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> posters = userContentPosterService.findSeriesPosters(userId, seriesTmdbIds);
        return posters == null ? Map.of() : posters;
    }

    private ContentType watchedContentTypeFor(ContentType type) {
        return type == ContentType.MOVIE ? ContentType.MOVIE : ContentType.EPISODE;
    }

    private List<DiaryEntryResponseDTO> toDiaryEntryResponseDtos(List<DiaryEntry> entries,
            Map<UUID, String> customPosterByContentId) {
        return entries.stream()
                .map(entry -> toDiaryEntryResponseDto(entry, customPosterByContentId))
                .toList();
    }

    private DiaryEntryResponseDTO toDiaryEntryResponseDto(DiaryEntry entry, Map<UUID, String> customPosterByContentId) {
        return diaryEntryMapper.diaryEntryToResponseDto(entry, false)
                .withCustomPosterUrl(customPosterByContentId.get(entry.getContent().getId()));
    }

    private List<DiaryEntryResponseDTO> promoteTop5First(List<DiaryEntry> entries, UUID userId,
            List<ContentType> top5Types, Map<UUID, String> customPosterByContentId) {
        Set<UUID> top5ContentIds = top5Types.stream()
                .flatMap(t -> top5EntryRepository.findByUserIdAndTypeWithContentOrderByPositionAsc(userId, t).stream())
                .map(entry -> entry.getContent().getId())
                .collect(Collectors.toSet());

        return entries.stream()
                .sorted(Comparator.comparing((DiaryEntry d) -> !top5ContentIds.contains(d.getContent().getId())))
                .map(entry -> toDiaryEntryResponseDto(entry, customPosterByContentId))
                .toList();
    }

    private List<DiaryEntryResponseDTO> sortBottomRated(List<DiaryEntry> entries,
            Map<UUID, String> customPosterByContentId) {
        return entries.stream()
                .sorted(Comparator.comparing(DiaryEntry::getScore))
                .map(entry -> toDiaryEntryResponseDto(entry, customPosterByContentId))
                .toList();
    }

    private Map<UUID, String> loadPostersForOwner(UUID ownerId, Collection<DiaryEntry> entries) {
        List<UUID> contentIds = entries.stream()
                .map(DiaryEntry::getContent)
                .filter(java.util.Objects::nonNull)
                .map(Content::getId)
                .distinct()
                .toList();
        if (contentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> posters = userContentPosterService.findByUserAndContentIds(ownerId, contentIds);
        return posters == null ? Map.of() : posters;
    }

    @SafeVarargs
    private final List<DiaryEntry> collectDiaryEntries(Collection<DiaryEntry>... collections) {
        return Stream.of(collections)
                .flatMap(Collection::stream)
                .toList();
    }

    private List<LongestWatchedItemDTO> computeLongestWatched(UUID userId, ContentType type, LocalDate start, LocalDate end) {
        if (type == ContentType.MOVIE) {
            return diaryEntryRepository.findDistinctMovieContentByUserIdAndWatchedDateBetweenOrderByRuntimeDesc(
                            userId, start, end, PageRequest.of(0, YEAR_LONGEST_LIMIT))
                    .stream()
                    .map(c -> new LongestWatchedItemDTO(ContentType.MOVIE, c.getId(), c.getTmdbId(), null,
                            c.getRuntimeMinutes() == null ? 0 : c.getRuntimeMinutes()))
                    .toList();
        }
        return diaryEntryRepository.sumRuntimeMinutesByUserIdGroupBySeriesTmdbIdAndWatchedDateBetween(
                        userId, start, end, PageRequest.of(0, YEAR_LONGEST_LIMIT))
                .stream()
                .map(row -> new LongestWatchedItemDTO(ContentType.SERIES, row.getContentId(), null,
                        row.getSeriesTmdbId(), row.getTotalMinutes()))
                .toList();
    }

    private List<WatchCompanionCountDTO> computeTopWatchCompanions(UUID userId, ContentType contentType, LocalDate start, LocalDate end) {
        List<WatchCompanionRepository.CompanionWatchCount> rows = watchCompanionRepository
                .countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween(
                        userId, contentType, start, end, PageRequest.of(0, TOP_COMPANIONS_LIMIT));
        return toWatchCompanionCountDtos(rows);
    }

    private List<WatchCompanionCountDTO> computeTopWatchCompanionsAllTime(UUID userId) {
        List<WatchCompanionRepository.CompanionWatchCount> rows = watchCompanionRepository
                .countGroupedByCompanionUserIdAndContentTypeIn(
                        userId, Set.of(ContentType.MOVIE, ContentType.EPISODE), PageRequest.of(0, TOP_COMPANIONS_LIMIT));
        return toWatchCompanionCountDtos(rows);
    }

    private List<WatchCompanionCountDTO> toWatchCompanionCountDtos(List<WatchCompanionRepository.CompanionWatchCount> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, User> usersById = userRepository
                .findAllById(rows.stream().map(WatchCompanionRepository.CompanionWatchCount::getCompanionUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, u -> u));
        return rows.stream()
                .map(row -> new WatchCompanionCountDTO(
                        userMapper.userToUserPreviewDto(usersById.get(row.getCompanionUserId())), row.getCount()))
                .toList();
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

}
