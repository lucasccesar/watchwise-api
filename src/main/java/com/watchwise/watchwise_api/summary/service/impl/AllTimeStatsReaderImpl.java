package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.mapper.DiaryEntryMapper;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import com.watchwise.watchwise_api.summary.dto.AllTimeEditionStatsDTO;
import com.watchwise.watchwise_api.summary.dto.ContentWatchCountDTO;
import com.watchwise.watchwise_api.summary.dto.CountryCountDTO;
import com.watchwise.watchwise_api.summary.dto.DecadeCountDTO;
import com.watchwise.watchwise_api.summary.dto.RatingCountDTO;
import com.watchwise.watchwise_api.summary.dto.YearCountDTO;
import com.watchwise.watchwise_api.summary.repository.AllTimeStatsQueryRepository;
import com.watchwise.watchwise_api.summary.repository.ProfileSummaryQueryRepository;
import com.watchwise.watchwise_api.summary.service.AllTimeStatsReader;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AllTimeStatsReaderImpl implements AllTimeStatsReader {

    private static final int TOP_LIMIT = 10;
    private static final double AVERAGE_DAYS_PER_MONTH = 30.44;

    private final AllTimeStatsQueryRepository queryRepository;
    private final ProfileSummaryQueryRepository profileSummaryQueryRepository;
    private final ContentRepository contentRepository;
    private final ContentMapper contentMapper;
    private final DiaryEntryMapper diaryEntryMapper;
    private final Top5EntryRepository top5EntryRepository;
    private final UserContentPosterService userContentPosterService;

    @Override
    public AllTimeEditionStatsDTO read(UUID userId, ContentType type) {
        ContentType watchedContentType = type == ContentType.MOVIE ? ContentType.MOVIE : ContentType.EPISODE;

        long watchedCount = profileSummaryQueryRepository.countByUserIdAndContentType(userId, watchedContentType);
        long minutesWatched = profileSummaryQueryRepository
                .sumRuntimeMinutesByUserIdAndContentType(userId, watchedContentType);
        LocalDate firstWatched = queryRepository.findMinWatchedDateByUserIdAndContentType(userId, watchedContentType);
        long daysSinceFirstWatched = firstWatched == null
                ? 1L
                : Math.max(1L, ChronoUnit.DAYS.between(firstWatched, LocalDate.now()) + 1L);
        double averageMinutesPerDay = minutesWatched / (double) daysSinceFirstWatched;

        List<YearCountDTO> watchCountByYear = queryRepository
                .countByUserIdAndContentTypeGroupByYear(userId, watchedContentType.name()).stream()
                .map(row -> new YearCountDTO(row.getYear(), row.getCount()))
                .toList();
        List<DecadeCountDTO> watchCountByDecade = (type == ContentType.MOVIE
                ? queryRepository.countDistinctMoviesByDecade(userId)
                : queryRepository.countDistinctSeriesByDecade(userId)).stream()
                .map(row -> new DecadeCountDTO(row.getDecade(), row.getCount()))
                .toList();
        List<CountryCountDTO> watchCountByCountry = (type == ContentType.MOVIE
                ? queryRepository.countDistinctMoviesByCountry(userId)
                : queryRepository.countDistinctSeriesByCountry(userId)).stream()
                .map(row -> new CountryCountDTO(row.getCountry(), row.getCount()))
                .toList();
        List<GenreCountDTO> genreCounts = (type == ContentType.MOVIE
                ? profileSummaryQueryRepository.countDistinctMoviesByGenre(userId)
                : profileSummaryQueryRepository.countDistinctEpisodesByGenre(userId)).stream()
                .map(row -> new GenreCountDTO(row.getGenre(), row.getCount()))
                .toList();
        List<RatingCountDTO> ratingsDistribution = profileSummaryQueryRepository
                .countLatestScoresByUserIdAndContentType(userId, watchedContentType.name()).stream()
                .map(row -> new RatingCountDTO(row.getScore(), row.getCount()))
                .toList();

        List<ContentWatchCountDTO> mostLoggedContent = readMostLoggedContent(userId, type);
        List<DiaryEntry> topRatedRaw = queryRepository.findTopRatedByUserIdAndContentType(
                userId, type, PageRequest.of(0, TOP_LIMIT));
        List<DiaryEntry> bottomRatedRaw = queryRepository.findBottomRatedByUserIdAndContentType(
                userId, type, PageRequest.of(0, TOP_LIMIT));
        Map<UUID, String> customPosters = loadPostersForOwner(userId, topRatedRaw, bottomRatedRaw);
        List<DiaryEntryResponseDTO> topRated = promoteTop5First(topRatedRaw, userId, type, customPosters);
        List<DiaryEntryResponseDTO> bottomRated = sortBottomRated(bottomRatedRaw, customPosters);

        long totalTheaterVisits = type == ContentType.MOVIE
                ? queryRepository.countTheaterVisitsByUserId(userId)
                : 0L;

        return new AllTimeEditionStatsDTO(type, watchedCount, minutesWatched, totalTheaterVisits,
                averageMinutesPerDay * AVERAGE_DAYS_PER_MONTH, averageMinutesPerDay * 7,
                averageMinutesPerDay, watchCountByYear, watchCountByDecade, watchCountByCountry,
                mostLoggedContent, genreCounts, ratingsDistribution, topRated, bottomRated);
    }

    private List<ContentWatchCountDTO> readMostLoggedContent(UUID userId, ContentType type) {
        List<AllTimeStatsQueryRepository.ContentWatchCount> rows = type == ContentType.MOVIE
                ? queryRepository.countMostLoggedMovies(userId, PageRequest.of(0, TOP_LIMIT))
                : queryRepository.countMostLoggedSeries(userId, PageRequest.of(0, TOP_LIMIT));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, Content> contentById = contentRepository.findAllById(rows.stream()
                        .map(AllTimeStatsQueryRepository.ContentWatchCount::getContentId)
                        .toList()).stream()
                .collect(Collectors.toMap(Content::getId, Function.identity()));
        return rows.stream()
                .map(row -> new ContentWatchCountDTO(
                        contentMapper.contentToContentRefDto(contentById.get(row.getContentId())), row.getCount()))
                .toList();
    }

    @SafeVarargs
    private final Map<UUID, String> loadPostersForOwner(UUID ownerId, Collection<DiaryEntry>... collections) {
        List<UUID> contentIds = java.util.Arrays.stream(collections)
                .flatMap(Collection::stream)
                .map(DiaryEntry::getContent)
                .map(Content::getId)
                .distinct()
                .toList();
        if (contentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> posters = userContentPosterService.findByUserAndContentIds(ownerId, contentIds);
        return posters == null ? Map.of() : posters;
    }

    private List<DiaryEntryResponseDTO> promoteTop5First(List<DiaryEntry> entries, UUID userId,
            ContentType type, Map<UUID, String> customPosters) {
        var top5ContentIds = top5EntryRepository.findByUserIdAndTypeWithContentOrderByPositionAsc(userId, type).stream()
                .map(entry -> entry.getContent().getId())
                .collect(Collectors.toSet());
        return entries.stream()
                .sorted(Comparator.comparing((DiaryEntry entry) -> !top5ContentIds.contains(entry.getContent().getId())))
                .map(entry -> toDiaryEntryResponseDto(entry, customPosters))
                .toList();
    }

    private List<DiaryEntryResponseDTO> sortBottomRated(List<DiaryEntry> entries, Map<UUID, String> customPosters) {
        return entries.stream()
                .sorted(Comparator.comparing(DiaryEntry::getScore))
                .map(entry -> toDiaryEntryResponseDto(entry, customPosters))
                .toList();
    }

    private DiaryEntryResponseDTO toDiaryEntryResponseDto(DiaryEntry entry, Map<UUID, String> customPosters) {
        return diaryEntryMapper.diaryEntryToResponseDto(entry, false)
                .withCustomPosterUrl(customPosters.get(entry.getContent().getId()));
    }
}
