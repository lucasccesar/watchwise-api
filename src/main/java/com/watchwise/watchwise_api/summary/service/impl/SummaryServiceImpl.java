package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.mapper.DiaryEntryMapper;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.diaryentry.service.DiaryEntryService;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.summary.dto.AllTimeStatsResponseDTO;
import com.watchwise.watchwise_api.summary.dto.ContentWatchCountDTO;
import com.watchwise.watchwise_api.summary.dto.CountryCountDTO;
import com.watchwise.watchwise_api.summary.dto.DailyMinutesDTO;
import com.watchwise.watchwise_api.summary.dto.DayOfWeekCountDTO;
import com.watchwise.watchwise_api.summary.dto.DecadeCountDTO;
import com.watchwise.watchwise_api.summary.dto.DailyWatchCountDTO;
import com.watchwise.watchwise_api.summary.dto.EpisodeRatingsGridResponseDTO;
import com.watchwise.watchwise_api.summary.dto.EpisodeScoreDTO;
import com.watchwise.watchwise_api.summary.dto.HomeSummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.LongestWatchedItemDTO;
import com.watchwise.watchwise_api.summary.dto.MonthCountDTO;
import com.watchwise.watchwise_api.summary.dto.MonthInReviewResponseDTO;
import com.watchwise.watchwise_api.summary.dto.RatingCountDTO;
import com.watchwise.watchwise_api.summary.dto.RecentActivityItemDTO;
import com.watchwise.watchwise_api.summary.dto.RecentActivityStatus;
import com.watchwise.watchwise_api.summary.dto.SeriesWatchTimeDTO;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.WatchCompanionCountDTO;
import com.watchwise.watchwise_api.summary.dto.WatchTimeDTO;
import com.watchwise.watchwise_api.summary.dto.YearCountDTO;
import com.watchwise.watchwise_api.summary.dto.YearInReviewResponseDTO;
import com.watchwise.watchwise_api.summary.service.SummaryService;
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

    private static final int RECENT_EPISODES_LIMIT = 4;
    private static final int RECENT_REVIEWS_LIMIT = 5;
    private static final int RECENT_ACTIVITY_LIMIT = 6;
    private static final int WATCH_TIME_WINDOW_DAYS = 30;
    private static final int HOME_NEXT_EPISODES_LIMIT = 6;
    private static final int HOME_RECENTLY_WATCHED_LIMIT = 4;
    private static final int MONTH_TOP_LIMIT = 6;
    private static final int YEAR_TOP_LIMIT = 10;
    private static final int ALL_TIME_TOP_LIMIT = 10;
    private static final int TOP_SERIES_LIMIT = 3;
    private static final int TOP_LONGEST_MOVIES_LIMIT = 3;
    private static final int TOP_COMPANIONS_LIMIT = 3;
    private static final int YEAR_LONGEST_LIMIT = 10;
    private static final double AVERAGE_DAYS_PER_MONTH = 30.44;
    private static final Set<ContentType> ALLOWED_SUMMARY_TYPES = Set.of(ContentType.MOVIE, ContentType.SERIES);

    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final DiaryEntryService diaryEntryService;
    private final DroppedEntryRepository droppedEntryRepository;
    private final ContentRepository contentRepository;
    private final ContentMapper contentMapper;
    private final DiaryEntryMapper diaryEntryMapper;
    private final Top5EntryRepository top5EntryRepository;
    private final WatchCompanionRepository watchCompanionRepository;
    private final UserMapper userMapper;

    @Override
    public SummaryResponseDTO getSummary(UUID viewerId, UUID userId, ContentType type) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        assertCanViewSummary(viewerId, userId, target);

        if (type == null || !ALLOWED_SUMMARY_TYPES.contains(type)) {
            throw new BadRequestException("type must be one of: MOVIE, SERIES");
        }

        ContentType watchedContentType = watchedContentTypeFor(type);

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

    @Override
    public HomeSummaryResponseDTO getHomeSummary(UUID viewerId, UUID userId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewSummary(viewerId, userId, target);

        long totalMinutesWatched = diaryEntryRepository.sumRuntimeMinutesByUserId(userId);
        long totalMoviesWatched = diaryEntryRepository.countByUserIdAndContentType(userId, ContentType.MOVIE);
        long totalEpisodesWatched = diaryEntryRepository.countByUserIdAndContentType(userId, ContentType.EPISODE);

        List<SeriesInProgressResponseDTO> nextEpisodes = diaryEntryRepository
                .findSeriesInProgressByUserId(userId, PageRequest.of(0, HOME_NEXT_EPISODES_LIMIT))
                .map(row -> new SeriesInProgressResponseDTO(
                        row.getSeriesTmdbId(), row.getMaxSeasonNumber(), row.getMaxEpisodeNumber(), row.getLastWatchedDate()))
                .getContent();

        LocalDate windowEnd = LocalDate.now();
        LocalDate windowStart = windowEnd.minusDays(WATCH_TIME_WINDOW_DAYS);

        List<DailyWatchCountDTO> watchCountByDayLast30Days = diaryEntryRepository
                .countByUserIdAndWatchedDateBetween(userId, windowStart, windowEnd).stream()
                .map(row -> new DailyWatchCountDTO(row.getWatchedDate(), row.getCount()))
                .toList();

        List<GenreCountDTO> genreCountsMoviesLast30Days = diaryEntryRepository
                .countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(userId, windowStart, windowEnd).stream()
                .map(row -> new GenreCountDTO(row.getGenre(), row.getCount()))
                .toList();
        List<GenreCountDTO> genreCountsSeriesLast30Days = diaryEntryRepository
                .countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(userId, windowStart, windowEnd).stream()
                .map(row -> new GenreCountDTO(row.getGenre(), row.getCount()))
                .toList();

        List<DiaryEntryResponseDTO> recentlyWatched = computeRecentlyWatched(userId);

        return new HomeSummaryResponseDTO(totalMinutesWatched, totalMoviesWatched, totalEpisodesWatched, nextEpisodes,
                watchCountByDayLast30Days, genreCountsMoviesLast30Days, genreCountsSeriesLast30Days, recentlyWatched);
    }

    private List<DiaryEntryResponseDTO> computeRecentlyWatched(UUID userId) {
        PageRequest topN = PageRequest.of(0, HOME_RECENTLY_WATCHED_LIMIT);

        Stream<DiaryEntry> movies = diaryEntryRepository
                .findTopByUserIdAndContentTypeOrderByCreatedAtDesc(userId, ContentType.MOVIE, topN).stream();
        Stream<DiaryEntry> episodes = diaryEntryRepository
                .findTopByUserIdAndContentTypeOrderByCreatedAtDesc(userId, ContentType.EPISODE, topN).stream();

        return Stream.concat(movies, episodes)
                .sorted(Comparator.comparing(DiaryEntry::getCreatedAt).reversed())
                .limit(HOME_RECENTLY_WATCHED_LIMIT)
                .map(this::toDiaryEntryResponseDto)
                .toList();
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

        List<DiaryEntryResponseDTO> recentWatched = diaryEntryRepository
                .findByUserIdAndContentTypeAndWatchedDateBetweenOrderByWatchedDateDesc(userId, watchedContentType, start, end, topN)
                .stream().map(this::toDiaryEntryResponseDto).toList();

        List<DiaryEntry> topRatedRaw = diaryEntryRepository
                .findTopRatedByUserIdAndContentTypeAndWatchedDateBetween(userId, type, start, end, topN);
        List<DiaryEntry> bottomRatedRaw = diaryEntryRepository
                .findBottomRatedByUserIdAndContentTypeAndWatchedDateBetween(userId, type, start, end, topN);
        List<DiaryEntryResponseDTO> topRated = promoteTop5First(topRatedRaw, userId, List.of(type));
        List<DiaryEntryResponseDTO> bottomRated = promoteTop5First(bottomRatedRaw, userId, List.of(type));

        List<RatingCountDTO> ratingsDistribution = diaryEntryRepository
                .countByUserIdAndContentTypeAndWatchedDateBetweenGroupByScore(userId, watchedContentType, start, end)
                .stream().map(row -> new RatingCountDTO(row.getScore(), row.getCount())).toList();

        long watchCount = diaryEntryRepository.countByUserIdAndContentTypeAndWatchedDateBetween(userId, watchedContentType, start, end);
        long minutesWatched = diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(userId, watchedContentType, start, end);
        LocalDate firstWatchedDate = diaryEntryRepository
                .findMinWatchedDateByUserIdAndContentTypeAndWatchedDateBetween(userId, watchedContentType, start, end).orElse(null);
        LocalDate lastWatchedDate = diaryEntryRepository
                .findMaxWatchedDateByUserIdAndContentTypeAndWatchedDateBetween(userId, watchedContentType, start, end).orElse(null);

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
                        .stream().map(row -> new SeriesWatchTimeDTO(row.getSeriesTmdbId(), row.getTotalMinutes())).toList()
                : List.of();

        List<com.watchwise.watchwise_api.content.dto.ContentRefDTO> topLongestMovies = type == ContentType.MOVIE
                ? diaryEntryRepository.findDistinctMovieContentByUserIdAndWatchedDateBetweenOrderByRuntimeDesc(
                                userId, start, end, PageRequest.of(0, TOP_LONGEST_MOVIES_LIMIT))
                        .stream().map(contentMapper::contentToContentRefDto).toList()
                : List.of();

        List<WatchCompanionCountDTO> topWatchCompanions = computeTopWatchCompanions(userId, watchedContentType, start, end);

        return new MonthInReviewResponseDTO(recentWatched, topRated, bottomRated, ratingsDistribution, watchCount,
                minutesWatched, firstWatchedDate, lastWatchedDate, minutesPerDay, watchCountByDayOfWeek, genreCounts,
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
        List<DiaryEntryResponseDTO> topRated = promoteTop5First(topRatedRaw, userId, List.of(type));
        List<DiaryEntryResponseDTO> bottomRated = promoteTop5First(bottomRatedRaw, userId, List.of(type));

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

        LocalDate firstWatchedDate = diaryEntryRepository
                .findMinWatchedDateByUserIdAndContentTypeAndWatchedDateBetween(userId, watchedContentType, start, end).orElse(null);
        LocalDate lastWatchedDate = diaryEntryRepository
                .findMaxWatchedDateByUserIdAndContentTypeAndWatchedDateBetween(userId, watchedContentType, start, end).orElse(null);

        List<LongestWatchedItemDTO> longestWatched = computeLongestWatched(userId, type, start, end);

        List<GenreCountDTO> genreCounts = (type == ContentType.MOVIE
                ? diaryEntryRepository.countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(userId, start, end)
                : diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(userId, start, end))
                .stream().map(row -> new GenreCountDTO(row.getGenre(), row.getCount())).toList();

        List<WatchCompanionCountDTO> topWatchCompanions = computeTopWatchCompanions(userId, watchedContentType, start, end);

        return new YearInReviewResponseDTO(ratingsDistribution, watchCount, minutesWatched, averageMinutesPerMonth,
                averageMinutesPerWeek, averageMinutesPerDay, watchCountByMonth, watchCountByDayOfWeek,
                firstWatchedDate, lastWatchedDate, longestWatched, genreCounts, topRated, bottomRated, topWatchCompanions);
    }

    @Override
    public AllTimeStatsResponseDTO getAllTimeStats(UUID viewerId, UUID userId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewSummary(viewerId, userId, target);

        long totalMoviesWatched = diaryEntryRepository.countByUserIdAndContentType(userId, ContentType.MOVIE);
        long totalEpisodesWatched = diaryEntryRepository.countByUserIdAndContentType(userId, ContentType.EPISODE);
        long totalMinutesWatched = diaryEntryRepository.sumRuntimeMinutesByUserId(userId);
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
        List<DiaryEntryResponseDTO> topRated = promoteTop5First(topRatedRaw, userId, List.of(ContentType.MOVIE, ContentType.SERIES));
        List<DiaryEntryResponseDTO> bottomRated = promoteTop5First(bottomRatedRaw, userId, List.of(ContentType.MOVIE, ContentType.SERIES));

        List<WatchCompanionCountDTO> topWatchCompanions = computeTopWatchCompanionsAllTime(userId);

        return new AllTimeStatsResponseDTO(totalMoviesWatched, totalEpisodesWatched, totalMinutesWatched, totalTheaterVisits,
                averageMinutesPerMonth, averageMinutesPerWeek, averageMinutesPerDay,
                watchCountByYearMovies, watchCountByYearEpisodes, watchCountByDecade, watchCountByCountry,
                mostLoggedContent, genreCountsMovies, genreCountsSeries, topRated, bottomRated, topWatchCompanions);
    }

    @Override
    public EpisodeRatingsGridResponseDTO getEpisodeRatingsGrid(UUID viewerId, UUID userId, String seriesTmdbId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewSummary(viewerId, userId, target);

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

        return new EpisodeRatingsGridResponseDTO(seriesTmdbId, episodes);
    }

    private ContentType watchedContentTypeFor(ContentType type) {
        return type == ContentType.MOVIE ? ContentType.MOVIE : ContentType.EPISODE;
    }

    private DiaryEntryResponseDTO toDiaryEntryResponseDto(DiaryEntry entry) {
        return diaryEntryMapper.diaryEntryToResponseDto(entry, false);
    }

    private List<DiaryEntryResponseDTO> promoteTop5First(List<DiaryEntry> entries, UUID userId, List<ContentType> top5Types) {
        Set<UUID> top5ContentIds = top5Types.stream()
                .flatMap(t -> top5EntryRepository.findByUserIdAndTypeWithContentOrderByPositionAsc(userId, t).stream())
                .map(entry -> entry.getContent().getId())
                .collect(Collectors.toSet());

        return entries.stream()
                .sorted(Comparator.comparing((DiaryEntry d) -> !top5ContentIds.contains(d.getContent().getId())))
                .map(this::toDiaryEntryResponseDto)
                .toList();
    }

    private List<LongestWatchedItemDTO> computeLongestWatched(UUID userId, ContentType type, LocalDate start, LocalDate end) {
        if (type == ContentType.MOVIE) {
            return diaryEntryRepository.findDistinctMovieContentByUserIdAndWatchedDateBetweenOrderByRuntimeDesc(
                            userId, start, end, PageRequest.of(0, YEAR_LONGEST_LIMIT))
                    .stream()
                    .map(c -> new LongestWatchedItemDTO(ContentType.MOVIE, c.getTmdbId(), null,
                            c.getRuntimeMinutes() == null ? 0 : c.getRuntimeMinutes()))
                    .toList();
        }
        return diaryEntryRepository.sumRuntimeMinutesByUserIdGroupBySeriesTmdbIdAndWatchedDateBetween(
                        userId, start, end, PageRequest.of(0, YEAR_LONGEST_LIMIT))
                .stream()
                .map(row -> new LongestWatchedItemDTO(ContentType.SERIES, null, row.getSeriesTmdbId(), row.getTotalMinutes()))
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
                ? diaryEntryRepository.countEntriesByGenreAndUserIdForMovies(userId)
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
