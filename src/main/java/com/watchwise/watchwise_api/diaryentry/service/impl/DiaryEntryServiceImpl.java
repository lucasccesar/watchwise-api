package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.diaryentry.dto.DeletionImpactDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DeletionImpactItemDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryBulkCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationResultDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryUpdateDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeasonProgressDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressAggregateDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressPageResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.entity.WatchCompanion;
import com.watchwise.watchwise_api.diaryentry.mapper.DiaryEntryMapper;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.diaryentry.service.DiaryEntryService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressReadRepository;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataRefreshService;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.service.WatchlistEntryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class DiaryEntryServiceImpl implements DiaryEntryService {

    private final DiaryEntryRepository diaryEntryRepository;
    private final UserRepository userRepository;
    private final ContentRepository contentRepository;
    private final ContentService contentService;
    private final FollowerRepository followerRepository;
    private final DiaryEntryMapper diaryEntryMapper;
    private final UserMapper userMapper;
    private final NewTransactionExecutor newTransactionExecutor;
    private final WatchlistEntryService watchlistEntryService;
    private final DroppedEntryRepository droppedEntryRepository;
    private final LikeService likeService;
    private final WatchCompanionRepository watchCompanionRepository;
    private final PageRequestFactory pageRequestFactory;
    private final TmdbClient tmdbClient;
    private final SeriesProgressReadRepository seriesProgressReadRepository;
    private final SeriesProgressMetadataRefreshService seriesProgressMetadataRefreshService;
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<DiaryEntryResponseDTO> getDiaryEntries(UUID viewerId, UUID userId, Integer year, Integer pageNumber, Integer pageSize,
            ContentType type, LocalDate dateFrom, LocalDate dateTo, Boolean hasReview) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        assertCanViewDiary(viewerId, userId, target);

        if (year != null && (dateFrom != null || dateTo != null)) {
            throw new BadRequestException("year cannot be combined with dateFrom/dateTo");
        }

        LocalDate effectiveDateFrom = dateFrom != null ? dateFrom : (year != null ? startOfYear(year) : null);
        LocalDate effectiveDateTo = dateTo != null ? dateTo : (year != null ? endOfYear(year) : null);
        boolean hasExtraFilters = type != null || effectiveDateFrom != null || effectiveDateTo != null || hasReview != null;

        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);

        Page<DiaryEntry> entries = hasExtraFilters
                ? diaryEntryRepository.findByUserIdWithFilters(userId, type, effectiveDateFrom, effectiveDateTo, hasReview, pageRequest)
                : diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(userId, pageRequest);

        List<UUID> entryIds = entries.getContent().stream().map(DiaryEntry::getId).toList();
        Set<UUID> likedEntryIds = likeService.getLikedDiaryEntryIds(viewerId, entryIds);
        Map<UUID, List<UserPreviewDTO>> watchedWithByEntryId = loadWatchedWith(entryIds);

        return entries.map(entry -> diaryEntryMapper.diaryEntryToResponseDto(entry, likedEntryIds.contains(entry.getId()),
                watchedWithByEntryId.getOrDefault(entry.getId(), List.of())));
    }

    @Override
    public Page<SeriesInProgressResponseDTO> getSeriesInProgress(UUID viewerId, UUID userId, Integer pageNumber, Integer pageSize) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        assertCanViewDiary(viewerId, userId, target);

        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);
        Page<DiaryEntryRepository.SeriesInProgress> seriesInProgress =
                diaryEntryRepository.findSeriesInProgressByUserId(userId, pageRequest);
        List<String> seriesTmdbIds = seriesInProgress.getContent().stream()
                .map(DiaryEntryRepository.SeriesInProgress::getSeriesTmdbId)
                .distinct()
                .toList();
        Map<String, Map<Integer, Long>> watchedEpisodeCountsBySeriesAndSeason =
                loadWatchedEpisodeCountsBySeriesAndSeason(userId, seriesTmdbIds);

        return seriesInProgress.map(row -> toSeriesInProgressResponse(
                row, watchedEpisodeCountsBySeriesAndSeason.getOrDefault(row.getSeriesTmdbId(), Map.of())));
    }

    @Override
    public SeriesInProgressPageResponseDTO getSeriesInProgress(
            UUID viewerId,
            UUID userId,
            Integer pageNumber,
            Integer pageSize,
            SeriesProgressReadRepository.SeriesProgressSort sortBy,
            Sort.Direction direction) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        assertCanViewDiary(viewerId, userId, target);

        SeriesProgressReadRepository.SeriesProgressSort effectiveSort = sortBy == null
                ? SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED
                : sortBy;
        Sort.Direction effectiveDirection = direction == null ? Sort.Direction.DESC : direction;
        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);

        Page<SeriesProgressReadRepository.SeriesProgressCandidate> allCandidates =
                seriesProgressReadRepository.findCandidatesByUserId(
                        userId, effectiveSort, effectiveDirection, Pageable.unpaged());
        List<SeriesProgressReadRepository.SeriesProgressCandidate> allRows = allCandidates.getContent();
        Map<String, SeriesProgressMetadataRefreshService.Snapshot> snapshots = refreshSnapshots(allRows);

        Page<SeriesProgressReadRepository.SeriesProgressCandidate> page =
                seriesProgressReadRepository.findCandidatesByUserId(
                        userId, effectiveSort, effectiveDirection, pageRequest);
        List<String> pageSeriesIds = page.getContent().stream()
                .map(SeriesProgressReadRepository.SeriesProgressCandidate::getSeriesTmdbId)
                .distinct()
                .toList();
        Map<String, Map<Integer, DiaryEntryRepository.SeasonProgress>> watchedProgress =
                loadWatchedProgress(userId, pageSeriesIds);

        List<SeriesInProgressResponseDTO> content = page.getContent().stream()
                .map(row -> toDetailedSeriesResponse(
                        row,
                        snapshots.get(row.getSeriesTmdbId()),
                        watchedProgress.getOrDefault(row.getSeriesTmdbId(), Map.of())))
                .toList();
        SeriesInProgressAggregateDTO aggregate = calculateAggregate(userId, allRows, snapshots);

        return new SeriesInProgressPageResponseDTO(
                content,
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                aggregate);
    }

    private Map<String, SeriesProgressMetadataRefreshService.Snapshot> refreshSnapshots(
            List<SeriesProgressReadRepository.SeriesProgressCandidate> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }

        LocalDate today = LocalDate.now();
        Map<String, SeriesProgressMetadataRefreshService.Snapshot> snapshots = new LinkedHashMap<>();
        rows.stream()
                .map(SeriesProgressReadRepository.SeriesProgressCandidate::getSeriesTmdbId)
                .distinct()
                .forEach(seriesTmdbId -> snapshots.put(
                        seriesTmdbId,
                        seriesProgressMetadataRefreshService.refreshIfMissingOrExpired(seriesTmdbId, today)));
        return snapshots;
    }

    private Map<String, Map<Integer, DiaryEntryRepository.SeasonProgress>> loadWatchedProgress(
            UUID userId, List<String> seriesTmdbIds) {
        if (seriesTmdbIds.isEmpty()) {
            return Map.of();
        }

        List<DiaryEntryRepository.SeasonProgress> progress = Optional.ofNullable(
                diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(userId, seriesTmdbIds))
                .orElseGet(List::of);
        return progress.stream().collect(Collectors.groupingBy(
                DiaryEntryRepository.SeasonProgress::getSeriesTmdbId,
                LinkedHashMap::new,
                Collectors.toMap(
                        DiaryEntryRepository.SeasonProgress::getSeasonNumber,
                        row -> row,
                        (first, second) -> first,
                        LinkedHashMap::new)));
    }

    private SeriesInProgressResponseDTO toDetailedSeriesResponse(
            SeriesProgressReadRepository.SeriesProgressCandidate row,
            SeriesProgressMetadataRefreshService.Snapshot snapshot,
            Map<Integer, DiaryEntryRepository.SeasonProgress> watchedProgress) {
        SeriesProgressMetadataRefreshService.SeriesSnapshot series = snapshot == null
                ? null
                : snapshot.series();
        Integer totalReleasedEpisodeCount = series != null
                ? series.regularReleasedEpisodeCount()
                : row.getTotalReleasedEpisodeCount();
        Integer totalKnownRuntime = series != null ? series.totalKnownRuntime() : row.getTotalKnownRuntime();
        Long watchedEpisodeCount = valueOrZero(row.getWatchedEpisodeCount());
        Long watchedRuntimeMinutes = valueOrZero(row.getWatchedRuntimeMinutes());
        Long remainingEpisodeCount = row.getRemainingEpisodeCount() != null
                ? row.getRemainingEpisodeCount()
                : remainingEpisodes(totalReleasedEpisodeCount, watchedEpisodeCount);
        Long remainingRuntimeMinutes = remainingRuntime(totalKnownRuntime, watchedRuntimeMinutes);

        List<SeasonProgressDTO> seasonProgress = snapshot == null
                ? List.of()
                : snapshot.seasons().stream()
                .filter(season -> season.seasonNumber() != null && season.seasonNumber() > 0)
                .filter(season -> season.regularReleasedEpisodeCount() != null
                        && season.regularReleasedEpisodeCount() > 0)
                .sorted(Comparator.comparing(SeriesProgressMetadataRefreshService.SeasonSnapshot::seasonNumber))
                .map(season -> toDetailedSeasonProgress(season, watchedProgress.get(season.seasonNumber())))
                .toList();
        Double watchedPercentage = percentage(watchedEpisodeCount, totalReleasedEpisodeCount);

        return new SeriesInProgressResponseDTO(
                row.getSeriesTmdbId(),
                row.getMaxSeasonNumber(),
                row.getMaxEpisodeNumber(),
                row.getLastWatchedDate(),
                watchedEpisodeCount,
                totalReleasedEpisodeCount,
                watchedPercentage,
                seasonProgress,
                row.getLastWatchedSeasonNumber(),
                row.getLastWatchedEpisodeNumber(),
                watchedRuntimeMinutes,
                totalReleasedEpisodeCount,
                totalKnownRuntime,
                series != null ? series.lastReleasedEpisodeDate() : row.getLastReleasedEpisodeDate(),
                remainingEpisodeCount,
                remainingRuntimeMinutes);
    }

    private SeasonProgressDTO toDetailedSeasonProgress(
            SeriesProgressMetadataRefreshService.SeasonSnapshot season,
            DiaryEntryRepository.SeasonProgress watched) {
        Long watchedEpisodeCount = watched == null ? 0L : valueOrZero(watched.getWatchedEpisodeCount());
        Long watchedRuntimeMinutes = watched == null ? 0L : valueOrZero(watched.getWatchedRuntimeMinutes());
        Integer totalEpisodeCount = season.regularReleasedEpisodeCount();
        return new SeasonProgressDTO(
                season.seasonNumber(),
                watchedEpisodeCount,
                totalEpisodeCount,
                percentage(watchedEpisodeCount, totalEpisodeCount),
                watchedRuntimeMinutes,
                remainingEpisodes(totalEpisodeCount, watchedEpisodeCount),
                remainingRuntime(season.totalKnownRuntime(), watchedRuntimeMinutes));
    }

    private SeriesInProgressAggregateDTO calculateAggregate(
            UUID userId,
            List<SeriesProgressReadRepository.SeriesProgressCandidate> rows,
            Map<String, SeriesProgressMetadataRefreshService.Snapshot> snapshots) {
        long releasedEpisodeCount = 0L;
        long knownRuntime = 0L;
        boolean completeRuntime = true;
        long fallbackWatchedEpisodeCount = 0L;
        long fallbackWatchedRuntimeMinutes = 0L;

        for (SeriesProgressReadRepository.SeriesProgressCandidate row : rows) {
            SeriesProgressMetadataRefreshService.Snapshot snapshot = snapshots.get(row.getSeriesTmdbId());
            SeriesProgressMetadataRefreshService.SeriesSnapshot series = snapshot == null ? null : snapshot.series();
            Integer released = series != null ? series.regularReleasedEpisodeCount() : row.getTotalReleasedEpisodeCount();
            Integer runtime = series != null ? series.totalKnownRuntime() : row.getTotalKnownRuntime();
            releasedEpisodeCount += valueOrZero(released);
            if (runtime == null) {
                completeRuntime = false;
            } else {
                knownRuntime += runtime;
            }
            fallbackWatchedEpisodeCount += valueOrZero(row.getWatchedEpisodeCount());
            fallbackWatchedRuntimeMinutes += valueOrZero(row.getWatchedRuntimeMinutes());
        }

        SeriesProgressReadRepository.SeriesProgressTotals totals =
                seriesProgressReadRepository.findGlobalTotalsByUserId(userId);
        long watchedEpisodeCount = totals == null || totals.getWatchedEpisodeCount() == null
                ? fallbackWatchedEpisodeCount
                : totals.getWatchedEpisodeCount();
        long watchedRuntimeMinutes = totals == null || totals.getWatchedRuntimeMinutes() == null
                ? fallbackWatchedRuntimeMinutes
                : totals.getWatchedRuntimeMinutes();
        Long remainingRuntimeMinutes = completeRuntime
                ? Math.max(knownRuntime - watchedRuntimeMinutes, 0L)
                : null;

        return new SeriesInProgressAggregateDTO(
                rows.stream().map(SeriesProgressReadRepository.SeriesProgressCandidate::getSeriesTmdbId).distinct().count(),
                watchedEpisodeCount,
                releasedEpisodeCount,
                Math.max(releasedEpisodeCount - watchedEpisodeCount, 0L),
                remainingRuntimeMinutes);
    }

    private Long remainingEpisodes(Integer totalEpisodeCount, Long watchedEpisodeCount) {
        return totalEpisodeCount == null
                ? null
                : Math.max(totalEpisodeCount.longValue() - valueOrZero(watchedEpisodeCount), 0L);
    }

    private Long remainingRuntime(Integer totalRuntimeMinutes, Long watchedRuntimeMinutes) {
        return totalRuntimeMinutes == null
                ? null
                : Math.max(totalRuntimeMinutes.longValue() - valueOrZero(watchedRuntimeMinutes), 0L);
    }

    private Double percentage(Long watchedEpisodeCount, Integer totalEpisodeCount) {
        if (totalEpisodeCount == null || totalEpisodeCount <= 0) {
            return null;
        }
        return Math.min(100.0, valueOrZero(watchedEpisodeCount) * 100.0 / totalEpisodeCount);
    }

    private long valueOrZero(Number value) {
        return value == null ? 0L : value.longValue();
    }

    private Map<String, Map<Integer, Long>> loadWatchedEpisodeCountsBySeriesAndSeason(
            UUID userId, List<String> seriesTmdbIds) {
        if (seriesTmdbIds.isEmpty()) {
            return Map.of();
        }

        List<DiaryEntryRepository.SeasonProgressCount> counts = Optional.ofNullable(
                diaryEntryRepository.findWatchedEpisodeCountsByUserIdAndSeriesTmdbIds(userId, seriesTmdbIds))
                .orElseGet(List::of);
        return counts.stream().collect(Collectors.groupingBy(
                DiaryEntryRepository.SeasonProgressCount::getSeriesTmdbId,
                LinkedHashMap::new,
                Collectors.toMap(
                        DiaryEntryRepository.SeasonProgressCount::getSeasonNumber,
                        DiaryEntryRepository.SeasonProgressCount::getWatchedEpisodeCount,
                        Long::sum,
                        LinkedHashMap::new)));
    }

    private SeriesInProgressResponseDTO toSeriesInProgressResponse(
            DiaryEntryRepository.SeriesInProgress row, Map<Integer, Long> watchedEpisodeCountsBySeason) {
        TmdbTvFullDetails details = tmdbClient
                .getTvFullDetails(row.getSeriesTmdbId(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)
                .toOptional()
                .orElseThrow(this::tmdbUnavailable);

        SeriesProgressDetails progress = calculateSeriesProgress(
                row.getSeriesTmdbId(), details, watchedEpisodeCountsBySeason);
        Integer totalEpisodeCount = progress.totalEpisodeCount();
        Double watchedPercentage = totalEpisodeCount == null
                ? null
                : Math.min(100.0, row.getWatchedEpisodeCount() * 100.0 / totalEpisodeCount);

        return new SeriesInProgressResponseDTO(
                row.getSeriesTmdbId(), row.getMaxSeasonNumber(), row.getMaxEpisodeNumber(), row.getLastWatchedDate(),
                row.getWatchedEpisodeCount(), totalEpisodeCount, watchedPercentage, progress.seasonProgress());
    }

    private SeriesProgressDetails calculateSeriesProgress(
            String seriesTmdbId, TmdbTvFullDetails details, Map<Integer, Long> watchedEpisodeCountsBySeason) {
        if (details.seasons() == null) {
            return new SeriesProgressDetails(List.of(), null);
        }

        List<SeasonProgressDTO> seasonProgress = details.seasons().stream()
                .filter(Objects::nonNull)
                .map(TmdbSeasonSummary::seasonNumber)
                .filter(seasonNumber -> seasonNumber != null && seasonNumber > 0)
                .distinct()
                .sorted()
                .map(seasonNumber -> toSeasonProgress(
                        seriesTmdbId, seasonNumber, watchedEpisodeCountsBySeason.getOrDefault(seasonNumber, 0L)))
                .filter(Objects::nonNull)
                .toList();

        int totalEpisodeCount = seasonProgress.stream()
                .mapToInt(SeasonProgressDTO::totalEpisodeCount)
                .sum();
        return totalEpisodeCount > 0
                ? new SeriesProgressDetails(seasonProgress, totalEpisodeCount)
                : new SeriesProgressDetails(List.of(), null);
    }

    private SeasonProgressDTO toSeasonProgress(String seriesTmdbId, Integer seasonNumber, long watchedEpisodeCount) {
        int totalEpisodeCount = airedEpisodeCount(fetchSeasonDetails(
                seriesTmdbId, seasonNumber, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE));
        if (totalEpisodeCount == 0) {
            return null;
        }

        double watchedPercentage = Math.min(100.0, watchedEpisodeCount * 100.0 / totalEpisodeCount);
        return new SeasonProgressDTO(seasonNumber, watchedEpisodeCount, totalEpisodeCount, watchedPercentage);
    }

    private record SeriesProgressDetails(List<SeasonProgressDTO> seasonProgress, Integer totalEpisodeCount) {
    }

    @Override
    public Page<DiaryEntryResponseDTO> getReviewsForContent(UUID viewerId, UUID contentId, Integer pageNumber, Integer pageSize) {
        if (!contentRepository.existsById(contentId)) {
            throw new NotFoundException("Content not found");
        }

        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);
        Page<DiaryEntry> reviews = diaryEntryRepository.findReviewsByContentId(contentId, viewerId, pageRequest);

        List<UUID> entryIds = reviews.getContent().stream().map(DiaryEntry::getId).toList();
        Set<UUID> likedEntryIds = likeService.getLikedDiaryEntryIds(viewerId, entryIds);
        Map<UUID, List<UserPreviewDTO>> watchedWithByEntryId = loadWatchedWith(entryIds);

        return reviews.map(entry -> diaryEntryMapper.diaryEntryToResponseDto(entry, likedEntryIds.contains(entry.getId()),
                watchedWithByEntryId.getOrDefault(entry.getId(), List.of())));
    }

    private void assertCanViewDiary(UUID viewerId, UUID targetUserId, User target) {
        if (Boolean.TRUE.equals(target.getIsProfilePublic()) || viewerId.equals(targetUserId)) {
            return;
        }

        boolean viewerFollowsTarget = followerRepository
                .existsByFollowerIdAndFollowedIdAndStatus(viewerId, targetUserId, FollowStatus.ACCEPTED);

        if (!viewerFollowsTarget) {
            throw new ForbiddenException("This user profile is private");
        }
    }

    private LocalDate startOfYear(Integer year) {
        try {
            return LocalDate.of(year, 1, 1);
        } catch (DateTimeException e) {
            throw new BadRequestException("year is invalid");
        }
    }

    private LocalDate endOfYear(Integer year) {
        return LocalDate.of(year, 12, 31);
    }

    @Override
    @Transactional
    public DiaryEntryCreationResultDTO createDiaryEntry(UUID userId, DiaryEntryCreationDTO diaryEntryCreationDTO) {
        assertWatchedDateNotInFuture(diaryEntryCreationDTO.watchedDate());
        List<UUID> companionIds = validateCompanions(userId, diaryEntryCreationDTO.watchedWith());
        ResolvedContentRef resolvedContent = resolveContentRefForCreation(userId, diaryEntryCreationDTO);
        ContentRefDTO contentRef = contentService.getOrCreateReference(resolvedContent.content(), resolvedContent.trustedRuntimeMinutes());

        User user = userRepository.getReferenceById(userId);
        Content content = contentRepository.getReferenceById(contentRef.id());

        DiaryEntry entry;
        try {
            entry = persistDiaryEntry(
                    user, content,
                    diaryEntryCreationDTO.comment(), diaryEntryCreationDTO.score(), diaryEntryCreationDTO.watchedDate(),
                    diaryEntryCreationDTO.isRewatch(), diaryEntryCreationDTO.watchedInTheater(),
                    diaryEntryCreationDTO.customPosterUrl(), false, false);
        } catch (DataIntegrityViolationException e) {
            throw mapWatchNumberConflict(e);
        }
        saveCompanions(entry, companionIds);

        removeFromWatchlistAndDropped(userId, contentRef);

        CompletionSignal completion = triggerCompletionCascade(userId, content, entry.getWatchedDate(), content.getType());

        List<UUID> resultIds = Stream.of(entry, completion.completedSeason(), completion.completedSeries())
                .filter(Objects::nonNull)
                .map(DiaryEntry::getId)
                .toList();
        Map<UUID, List<UserPreviewDTO>> watchedWithByEntryId = loadWatchedWith(resultIds);

        return new DiaryEntryCreationResultDTO(
                diaryEntryMapper.diaryEntryToResponseDto(entry, false, watchedWithByEntryId.getOrDefault(entry.getId(), List.of())),
                completion.completedSeason() != null
                        ? diaryEntryMapper.diaryEntryToResponseDto(completion.completedSeason(), false,
                                watchedWithByEntryId.getOrDefault(completion.completedSeason().getId(), List.of()))
                        : null,
                completion.completedSeries() != null
                        ? diaryEntryMapper.diaryEntryToResponseDto(completion.completedSeries(), false,
                                watchedWithByEntryId.getOrDefault(completion.completedSeries().getId(), List.of()))
                        : null);
    }

    private void removeFromWatchlistAndDropped(UUID userId, ContentRefDTO loggedContent) {
        if (loggedContent.type() == ContentType.MOVIE) {
            removeContentFromWatchlistAndDropped(userId, ContentType.MOVIE, loggedContent.id());
            return;
        }
        if (loggedContent.type() == ContentType.SERIES) {
            removeContentFromWatchlistAndDropped(userId, ContentType.SERIES, loggedContent.id());
            return;
        }

        removeSeriesFromWatchlistAndDropped(userId, loggedContent.seriesTmdbId());
    }

    private void removeSeriesFromWatchlistAndDropped(UUID userId, String seriesTmdbId) {
        contentRepository.findByTmdbIdAndType(seriesTmdbId, ContentType.SERIES)
                .ifPresent(series -> removeContentFromWatchlistAndDropped(userId, ContentType.SERIES, series.getId()));
    }

    private void removeContentFromWatchlistAndDropped(UUID userId, ContentType type, UUID contentId) {
        watchlistEntryService.removeEntryIfPresent(userId, type, contentId);
        droppedEntryRepository.findByUserIdAndTypeAndContentId(userId, type, contentId)
                .ifPresent(droppedEntryRepository::delete);
    }

    private ConflictException mapWatchNumberConflict(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof org.hibernate.exception.ConstraintViolationException cve
                && "uq_diary_entries_user_content_watch_number".equals(cve.getConstraintName())) {
            return new ConflictException("This watch was already logged by a concurrent request");
        }
        return new ConflictException("Diary entry could not be saved due to a conflict");
    }

    private DiaryEntry persistDiaryEntry(User user, Content content, String comment, Integer score,
            LocalDate watchedDate, Boolean requestedIsRewatch, Boolean watchedInTheater,
            String customPosterUrl, boolean autoGenerated, boolean ignore) {
        return persistDiaryEntry(user, content, comment, score, watchedDate, requestedIsRewatch, watchedInTheater,
                customPosterUrl, autoGenerated, ignore, null);
    }

    private DiaryEntry persistDiaryEntry(User user, Content content, String comment, Integer score,
            LocalDate watchedDate, Boolean requestedIsRewatch, Boolean watchedInTheater,
            String customPosterUrl, boolean autoGenerated, boolean ignore, Integer requestedWatchNumber) {
        assertWatchedInTheaterAllowed(content.getType(), watchedInTheater);

        int watchNumber;
        if (requestedWatchNumber != null) {
            watchNumber = requestedWatchNumber;
        } else {
            int maxWatchNumber = diaryEntryRepository.findMaxWatchNumber(user.getId(), content.getId());
            boolean honorRewatchFlag = Boolean.TRUE.equals(requestedIsRewatch)
                    && !(maxWatchNumber == 0 && participatesInCompletionTracking(content.getType()));
            watchNumber = Math.max(maxWatchNumber + 1, honorRewatchFlag ? 2 : 1);
        }

        LocalDateTime now = LocalDateTime.now();

        DiaryEntry entry = DiaryEntry.builder()
                .user(user)
                .content(content)
                .comment(comment)
                .score(score)
                .watchedDate(watchedDate)
                .watchNumber(watchNumber)
                .watchedInTheater(watchedInTheater)
                .customPosterUrl(customPosterUrl)
                .autoGenerated(autoGenerated)
                .ignore(ignore)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return diaryEntryRepository.saveAndFlush(entry);
    }

    @Override
    @Transactional
    public List<DiaryEntryResponseDTO> createDiaryEntriesInBulk(UUID userId, DiaryEntryBulkCreationDTO dto) {
        ContentRefCreationDTO content = dto.content();
        if (content.type() != ContentType.SEASON && content.type() != ContentType.SERIES) {
            throw new BadRequestException("Bulk logging only supports content of type SEASON or SERIES");
        }
        if (content.type() == ContentType.SEASON
                && (content.genres() != null || content.releaseYear() != null || content.countries() != null)) {
            throw new BadRequestException("genres, releaseYear and countries must not be provided when bulk logging a SEASON");
        }
        assertWatchedDateNotInFuture(dto.watchedDate());
        LocalDate cutoff = effectiveWatchedDate(dto.watchedDate());
        List<UUID> companionIds = validateCompanions(userId, dto.watchedWith());
        String language = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"))
                .getPreferredLanguage();

        List<DiaryEntry> created = new ArrayList<>();
        String seriesTmdbId;
        if (content.type() == ContentType.SEASON) {
            seriesTmdbId = content.seriesTmdbId();
            bulkLogSeason(userId, content.seriesTmdbId(), content.seasonNumber(), content.isSeriesFinale(),
                    dto.finaleEpisodeNumber(), dto.watchedDate(), cutoff, created, ContentType.SEASON, companionIds, language);
        } else {
            seriesTmdbId = content.tmdbId();
            bulkLogSeries(userId, content.tmdbId(), dto.finaleSeasonNumber(), dto.seasonFinaleEpisodeNumbers(),
                    dto.watchedDate(), cutoff, created, companionIds, language);
        }
        removeSeriesFromWatchlistAndDropped(userId, seriesTmdbId);

        List<UUID> createdIds = created.stream().map(DiaryEntry::getId).toList();
        Map<UUID, List<UserPreviewDTO>> watchedWithByEntryId = loadWatchedWith(createdIds);

        return created.stream()
                .map(entry -> diaryEntryMapper.diaryEntryToResponseDto(entry, false,
                        watchedWithByEntryId.getOrDefault(entry.getId(), List.of())))
                .toList();
    }

    @Override
    @Transactional
    public DiaryEntryResponseDTO updateDiaryEntry(UUID userId, UUID diaryEntryId, DiaryEntryUpdateDTO diaryEntryUpdateDTO) {
        DiaryEntry entry = findOwnedEntry(userId, diaryEntryId);

        if (diaryEntryUpdateDTO.comment() != null) {
            entry.setComment(diaryEntryUpdateDTO.comment());
        }
        if (diaryEntryUpdateDTO.score() != null) {
            entry.setScore(diaryEntryUpdateDTO.score());
        }
        if (diaryEntryUpdateDTO.watchedDate() != null) {
            assertWatchedDateNotInFuture(diaryEntryUpdateDTO.watchedDate());
            Content updatedContent = entry.getContent();
            assertWatchedDateNotBeforeRelease(diaryEntryUpdateDTO.watchedDate(),
                    resolveReleaseDate(updatedContent.getType(), updatedContent.getTmdbId(), updatedContent.getSeriesTmdbId(),
                            updatedContent.getSeasonNumber(), updatedContent.getEpisodeNumber()));
            entry.setWatchedDate(diaryEntryUpdateDTO.watchedDate());
        }
        if (diaryEntryUpdateDTO.watchedInTheater() != null) {
            assertWatchedInTheaterAllowed(entry.getContent().getType(), diaryEntryUpdateDTO.watchedInTheater());
            entry.setWatchedInTheater(diaryEntryUpdateDTO.watchedInTheater());
        }
        if (diaryEntryUpdateDTO.customPosterUrl() != null) {
            entry.setCustomPosterUrl(diaryEntryUpdateDTO.customPosterUrl());
        }

        if (diaryEntryUpdateDTO.watchedWith() != null) {
            List<UUID> companionIds = validateCompanions(userId, diaryEntryUpdateDTO.watchedWith());
            watchCompanionRepository.deleteByDiaryEntryId(entry.getId());
            saveCompanions(entry, companionIds);
        }

        entry.setAutoGenerated(false);
        entry.setIgnore(false);
        entry.setUpdatedAt(LocalDateTime.now());

        DiaryEntry saved = diaryEntryRepository.save(entry);
        boolean likedByMe = likeService.getLikedDiaryEntryIds(userId, List.of(saved.getId())).contains(saved.getId());
        List<UserPreviewDTO> watchedWith = loadWatchedWith(List.of(saved.getId())).getOrDefault(saved.getId(), List.of());
        return diaryEntryMapper.diaryEntryToResponseDto(saved, likedByMe, watchedWith);
    }

    private void assertWatchedInTheaterAllowed(ContentType contentType, Boolean watchedInTheater) {
        if (watchedInTheater != null && contentType != ContentType.MOVIE) {
            throw new BadRequestException("watchedInTheater can only be set for content of type MOVIE");
        }
    }

    private LocalDate effectiveWatchedDate(LocalDate watchedDate) {
        return watchedDate != null ? watchedDate : LocalDate.now();
    }

    private void assertWatchedDateNotInFuture(LocalDate watchedDate) {
        if (watchedDate != null && watchedDate.isAfter(LocalDate.now())) {
            throw new BadRequestException("watchedDate cannot be in the future");
        }
    }

    private void assertWatchedDateNotBeforeRelease(LocalDate watchedDate, LocalDate releaseDate) {
        if (watchedDate != null && releaseDate != null && watchedDate.isBefore(releaseDate)) {
            throw new BadRequestException("watchedDate cannot predate the content's release date (" + releaseDate + ")");
        }
    }

    private LocalDate resolveReleaseDate(ContentType type, String tmdbId, String seriesTmdbId, Integer seasonNumber,
            Integer episodeNumber) {
        return switch (type) {
            case MOVIE -> tmdbClient.getMovieFullDetails(tmdbId, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE).toOptional()
                    .map(TmdbMovieFullDetails::releaseDate).map(this::parseTmdbDate).orElse(null);
            case EPISODE -> tmdbClient.getSeasonFullDetails(seriesTmdbId, seasonNumber, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)
                    .toOptional().map(season -> episodeAirDate(season, episodeNumber)).orElse(null);
            case SEASON, SERIES -> null;
        };
    }

    private record ResolvedContentRef(ContentRefCreationDTO content, boolean trustedRuntimeMinutes) {
    }

    private ResolvedContentRef resolveContentRefForCreation(UUID userId, DiaryEntryCreationDTO dto) {
        ContentRefCreationDTO content = dto.content();
        if (content.type() != ContentType.EPISODE && content.type() != ContentType.MOVIE) {
            return new ResolvedContentRef(content, false);
        }

        String language = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"))
                .getPreferredLanguage();

        if (content.type() == ContentType.EPISODE) {
            return withDerivedEpisodeFinaleFlags(content, dto.watchedDate(), language);
        }

        if (content.tmdbId() == null || content.tmdbId().isBlank()) {
            return new ResolvedContentRef(content, false);
        }
        assertWatchedDateNotBeforeRelease(dto.watchedDate(),
                resolveReleaseDate(ContentType.MOVIE, content.tmdbId(), null, null, null));
        return new ResolvedContentRef(content, false);
    }

    private ResolvedContentRef withDerivedEpisodeFinaleFlags(ContentRefCreationDTO dto, LocalDate watchedDate, String language) {
        if (dto.seriesTmdbId() == null || dto.seriesTmdbId().isBlank() || dto.seasonNumber() == null || dto.episodeNumber() == null) {
            return new ResolvedContentRef(dto, false);
        }

        Optional<TmdbSeasonFullDetails> seasonDetails = tmdbClient
                .getSeasonFullDetails(dto.seriesTmdbId(), dto.seasonNumber(), language).toOptional();
        if (seasonDetails.isEmpty()) {
            return new ResolvedContentRef(dto, false);
        }

        assertWatchedDateNotBeforeRelease(watchedDate, episodeAirDate(seasonDetails.get(), dto.episodeNumber()));

        Integer runtimeMinutes = episodeRuntimeMinutesFromTmdb(seasonDetails.get()).get(dto.episodeNumber());
        boolean trustedRuntimeMinutes = runtimeMinutes != null;

        int airedEpisodeCount = airedEpisodeCount(seasonDetails.get());
        if (airedEpisodeCount == 0) {
            return new ResolvedContentRef(withRuntimeMinutes(dto, runtimeMinutes), trustedRuntimeMinutes);
        }

        boolean isSeasonFinale = dto.episodeNumber() == airedEpisodeCount;
        Boolean seasonFinaleFlag = isSeasonFinale ? Boolean.TRUE : null;
        Boolean seriesFinaleFlag = isSeasonFinale
                ? deriveSeriesFinaleFlag(dto.seriesTmdbId(), dto.seasonNumber(), language, effectiveWatchedDate(watchedDate))
                : null;

        ContentRefCreationDTO resolved = new ContentRefCreationDTO(dto.tmdbId(), dto.type(), dto.seriesTmdbId(), dto.seasonNumber(),
                dto.episodeNumber(), seasonFinaleFlag, seriesFinaleFlag, runtimeMinutes, dto.genres(), dto.releaseYear(), dto.countries());
        return new ResolvedContentRef(resolved, trustedRuntimeMinutes);
    }

    private ContentRefCreationDTO withRuntimeMinutes(ContentRefCreationDTO dto, Integer runtimeMinutes) {
        return new ContentRefCreationDTO(dto.tmdbId(), dto.type(), dto.seriesTmdbId(), dto.seasonNumber(), dto.episodeNumber(),
                dto.isSeasonFinale(), dto.isSeriesFinale(), runtimeMinutes, dto.genres(), dto.releaseYear(), dto.countries());
    }

    private Boolean deriveSeriesFinaleFlag(String seriesTmdbId, Integer seasonNumber, String language, LocalDate cutoff) {
        return tmdbClient.getTvFullDetails(seriesTmdbId, language).toOptional()
                .map(series -> latestAiredSeasonNumber(series.seasons(), cutoff))
                .filter(latest -> latest >= 1 && seasonNumber != null && seasonNumber == latest)
                .map(latest -> Boolean.TRUE)
                .orElse(null);
    }

    private List<UUID> validateCompanions(UUID ownerId, List<UUID> companionIds) {
        if (companionIds == null || companionIds.isEmpty()) {
            return List.of();
        }

        List<UUID> distinct = companionIds.stream().distinct().toList();
        for (UUID companionId : distinct) {
            if (companionId.equals(ownerId)) {
                throw new BadRequestException("watchedWith cannot include yourself");
            }
            if (!followerRepository.existsByFollowerIdAndFollowedIdAndStatus(ownerId, companionId, FollowStatus.ACCEPTED)) {
                throw new BadRequestException("watchedWith can only include users you follow");
            }
        }
        return distinct;
    }

    private void saveCompanions(DiaryEntry entry, List<UUID> companionIds) {
        if (companionIds == null || companionIds.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<WatchCompanion> companions = companionIds.stream()
                .map(companionId -> WatchCompanion.builder()
                        .diaryEntry(entry)
                        .user(userRepository.getReferenceById(companionId))
                        .createdAt(now)
                        .build())
                .toList();
        watchCompanionRepository.saveAll(companions);
    }

    private Map<UUID, List<UserPreviewDTO>> loadWatchedWith(List<UUID> diaryEntryIds) {
        if (diaryEntryIds.isEmpty()) {
            return Map.of();
        }
        return watchCompanionRepository.findByDiaryEntryIdIn(diaryEntryIds).stream()
                .collect(Collectors.groupingBy(wc -> wc.getDiaryEntry().getId(),
                        Collectors.mapping(wc -> userMapper.userToUserPreviewDto(wc.getUser()), Collectors.toList())));
    }

    private boolean participatesInCompletionTracking(ContentType contentType) {
        return contentType == ContentType.EPISODE || contentType == ContentType.SEASON;
    }

    @Override
    @Transactional
    public void deleteDiaryEntry(UUID userId, UUID diaryEntryId, boolean overrideProtectedEntries) {
        deleteDiaryEntry(userId, diaryEntryId, overrideProtectedEntries, new ArrayList<>());
    }

    @Override
    @Transactional
    public void deleteAllDiaryEntriesForSeries(UUID userId, String seriesTmdbId) {
        List<DiaryEntry> allEntries = Stream.of(
                        diaryEntryRepository.findEpisodeEntriesBySeriesForUser(userId, seriesTmdbId),
                        diaryEntryRepository.findAllSeasonEntriesInSeries(userId, seriesTmdbId),
                        diaryEntryRepository.findAllSeriesEntriesInSeries(userId, seriesTmdbId))
                .flatMap(List::stream)
                .toList();

        if (allEntries.isEmpty()) {
            return;
        }

        diaryEntryRepository.deleteAll(allEntries);
        diaryEntryRepository.flush();
    }

    private void deleteDiaryEntry(UUID userId, UUID diaryEntryId, boolean overrideProtectedEntries, List<DiaryEntry> cascadeDeleted) {
        DiaryEntry entry = findOwnedEntry(userId, diaryEntryId);
        Content content = entry.getContent();
        int watchNumber = entry.getWatchNumber();

        diaryEntryRepository.delete(entry);
        diaryEntryRepository.flush();

        if (content.getType() == ContentType.EPISODE) {
            retractSeasonIfIncomplete(userId, content.getSeriesTmdbId(), content.getSeasonNumber(), overrideProtectedEntries, cascadeDeleted);
        } else if (content.getType() == ContentType.SEASON) {
            retractSeriesIfIncomplete(userId, content.getSeriesTmdbId(), overrideProtectedEntries, cascadeDeleted);
        } else if (content.getType() == ContentType.SERIES) {
            wipeSeriesHistory(userId, content.getTmdbId(), watchNumber, overrideProtectedEntries, cascadeDeleted);
        }
    }

    private boolean deleteRespectingProtection(List<DiaryEntry> candidates, boolean overrideProtectedEntries, List<DiaryEntry> cascadeDeleted) {
        List<DiaryEntry> toDelete = candidates.stream()
                .filter(candidate -> overrideProtectedEntries || Boolean.TRUE.equals(candidate.getAutoGenerated()))
                .toList();

        if (toDelete.isEmpty()) {
            return false;
        }

        diaryEntryRepository.deleteAll(toDelete);
        diaryEntryRepository.flush();
        cascadeDeleted.addAll(toDelete);
        return true;
    }

    private void wipeSeriesHistory(UUID userId, String seriesTmdbId, int watchNumber, boolean overrideProtectedEntries, List<DiaryEntry> cascadeDeleted) {
        deleteRespectingProtection(computeSeriesWipeCandidates(userId, seriesTmdbId, watchNumber), overrideProtectedEntries, cascadeDeleted);
    }

    private void retractSeasonIfIncomplete(UUID userId, String seriesTmdbId, Integer seasonNumber, boolean overrideProtectedEntries,
            List<DiaryEntry> cascadeDeleted) {
        List<DiaryEntry> candidates = computeSeasonRetractionCandidates(userId, seriesTmdbId, seasonNumber);
        if (!deleteRespectingProtection(candidates, overrideProtectedEntries, cascadeDeleted)) {
            return;
        }

        retractSeriesIfIncomplete(userId, seriesTmdbId, overrideProtectedEntries, cascadeDeleted);
    }

    private List<DiaryEntry> computeSeasonRetractionCandidates(UUID userId, String seriesTmdbId, Integer seasonNumber) {
        Optional<Content> seasonFinaleEpisode = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(seriesTmdbId, seasonNumber, ContentType.EPISODE);
        if (seasonFinaleEpisode.isEmpty()) {
            return List.of();
        }

        int minCount = minEpisodeWatchCount(userId, seriesTmdbId, seasonNumber, seasonFinaleEpisode.get().getEpisodeNumber());

        Optional<Content> seasonContent = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(seriesTmdbId, seasonNumber, null, ContentType.SEASON);
        if (seasonContent.isEmpty()) {
            return List.of();
        }

        return diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(userId, seasonContent.get().getId(), minCount);
    }

    private void retractSeriesIfIncomplete(UUID userId, String seriesTmdbId, boolean overrideProtectedEntries, List<DiaryEntry> cascadeDeleted) {
        deleteRespectingProtection(computeSeriesRetractionCandidates(userId, seriesTmdbId), overrideProtectedEntries, cascadeDeleted);
    }

    private List<DiaryEntry> computeSeriesRetractionCandidates(UUID userId, String seriesTmdbId) {
        Optional<Content> seriesFinaleSeason = contentRepository
                .findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(seriesTmdbId, ContentType.SEASON);
        if (seriesFinaleSeason.isEmpty()) {
            return List.of();
        }

        int minMax = minSeasonWatchMax(userId, seriesTmdbId, seriesFinaleSeason.get().getSeasonNumber());

        Optional<Content> seriesContent = contentRepository.findByTmdbIdAndType(seriesTmdbId, ContentType.SERIES);
        if (seriesContent.isEmpty()) {
            return List.of();
        }

        return diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(userId, seriesContent.get().getId(), minMax);
    }

    private List<DiaryEntry> computeSeriesWipeCandidates(UUID userId, String seriesTmdbId, int watchNumber) {
        return Stream.of(
                        diaryEntryRepository.findEpisodeEntriesInSeriesByWatchNumber(userId, seriesTmdbId, watchNumber),
                        diaryEntryRepository.findSeasonEntriesInSeriesByWatchNumber(userId, seriesTmdbId, watchNumber),
                        diaryEntryRepository.findSeriesEntriesByWatchNumber(userId, seriesTmdbId, watchNumber))
                .flatMap(List::stream)
                .toList();
    }

    static final int MAX_BULK_EPISODES = 2000;

    private record EpisodeKey(Integer seasonNumber, Integer episodeNumber) {
    }

    private record BulkPassPlan(int targetWatchNumber, Set<EpisodeKey> existingTargetEpisodes) {
        private BulkPassPlan {
            existingTargetEpisodes = Set.copyOf(existingTargetEpisodes);
        }

        private boolean needsEpisode(EpisodeKey key) {
            return !existingTargetEpisodes.contains(key);
        }
    }

    private record BulkCompletionBoundary(List<Integer> eligibleEpisodes, Integer seriesFinaleSeasonNumber,
            int targetWatchNumber) {
        private BulkCompletionBoundary {
            eligibleEpisodes = List.copyOf(eligibleEpisodes);
        }
    }

    private void bulkLogSeason(UUID userId, String seriesTmdbId, Integer seasonNumber, Boolean isSeriesFinale,
            Integer explicitFinaleEpisodeNumber, LocalDate watchedDate, LocalDate cutoff, List<DiaryEntry> created, ContentType requestedType,
            List<UUID> companionIds, String language) {
        TmdbSeasonFullDetails seasonDetails = fetchSeasonDetails(seriesTmdbId, seasonNumber, language);
        int finaleEpisodeNumber = resolveSeasonFinaleEpisodeNumber(seriesTmdbId, seasonNumber, explicitFinaleEpisodeNumber,
                seasonDetails, cutoff);
        if (finaleEpisodeNumber > MAX_BULK_EPISODES && finaleEpisodeNumber > realSeasonEpisodeCount(seasonDetails)) {
            throw new BadRequestException("Season has more than " + MAX_BULK_EPISODES
                    + " episodes, exceeding the bulk log limit, and the requested episode count could not be verified against TMDB");
        }

        List<Integer> episodeNumbers = bulkEpisodeNumbers(seasonDetails, finaleEpisodeNumber, cutoff);
        BulkPassPlan passPlan = buildBulkPassPlan(
                episodeNumbers.stream().map(episodeNumber -> new EpisodeKey(seasonNumber, episodeNumber)).toList(),
                diaryEntryRepository.findEpisodeEntriesByUserIdAndSeriesTmdbIdAndSeasonNumber(userId, seriesTmdbId, seasonNumber));
        Map<Integer, Integer> episodeRuntimeMinutes = episodeRuntimeMinutesFromTmdb(seasonDetails);
        for (int episodeNumber : episodeNumbers) {
            DiaryEntry entry = bulkLogEpisode(userId, seriesTmdbId, seasonNumber, episodeNumber,
                    episodeNumber == finaleEpisodeNumber, isSeriesFinale, watchedDate, created, requestedType, companionIds,
                    episodeRuntimeMinutes.get(episodeNumber), true, passPlan,
                    new BulkCompletionBoundary(episodeNumbers, null, passPlan.targetWatchNumber()));
            if (entry != null) {
                created.add(entry);
            }
        }
    }

    private BulkPassPlan buildBulkPassPlan(List<EpisodeKey> eligibleEpisodes, List<DiaryEntry> history) {
        Set<EpisodeKey> eligibleEpisodeSet = Set.copyOf(eligibleEpisodes);
        Map<Integer, Set<EpisodeKey>> episodesByWatchNumber = history.stream()
                .filter(entry -> entry.getWatchNumber() != null && entry.getWatchNumber() > 0)
                .collect(Collectors.groupingBy(DiaryEntry::getWatchNumber,
                        Collectors.mapping(this::episodeKey, Collectors.toSet())));
        int maximumWatchNumber = episodesByWatchNumber.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        int targetWatchNumber = 1;
        while (targetWatchNumber <= maximumWatchNumber
                && episodesByWatchNumber.getOrDefault(targetWatchNumber, Set.of()).containsAll(eligibleEpisodeSet)) {
            targetWatchNumber++;
        }
        Set<EpisodeKey> existingTargetEpisodes = episodesByWatchNumber.getOrDefault(targetWatchNumber, Set.of());
        return new BulkPassPlan(targetWatchNumber, existingTargetEpisodes);
    }

    private EpisodeKey episodeKey(DiaryEntry entry) {
        Content content = entry.getContent();
        return new EpisodeKey(content.getSeasonNumber(), content.getEpisodeNumber());
    }

    private TmdbSeasonFullDetails fetchSeasonDetails(String seriesTmdbId, Integer seasonNumber, String language) {
        return tmdbClient.getSeasonFullDetails(seriesTmdbId, seasonNumber, language).toOptional().orElseThrow(this::tmdbUnavailable);
    }

    private Map<Integer, Integer> episodeRuntimeMinutesFromTmdb(TmdbSeasonFullDetails season) {
        if (season.episodes() == null) {
            return Map.of();
        }
        return season.episodes().stream()
                .filter(episode -> episode.episodeNumber() != null && episode.runtime() != null)
                .collect(Collectors.toMap(TmdbEpisodeSummary::episodeNumber, TmdbEpisodeSummary::runtime, (a, b) -> a));
    }

    private LocalDate episodeAirDate(TmdbSeasonFullDetails season, Integer episodeNumber) {
        if (season.episodes() == null) {
            return null;
        }
        return season.episodes().stream()
                .filter(episode -> Objects.equals(episodeNumber, episode.episodeNumber()))
                .map(TmdbEpisodeSummary::airDate)
                .map(this::parseTmdbDate)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private int realSeasonEpisodeCount(TmdbSeasonFullDetails season) {
        return season.episodes() == null ? 0 : season.episodes().size();
    }

    private int airedEpisodeCount(TmdbSeasonFullDetails season) {
        return airedEpisodeNumbers(season, LocalDate.now()).size();
    }

    private List<Integer> airedEpisodeNumbers(TmdbSeasonFullDetails season, LocalDate cutoff) {
        if (season.episodes() == null) {
            return List.of();
        }
        return season.episodes().stream()
                .filter(episode -> episode.episodeNumber() != null && episode.episodeNumber() > 0)
                .filter(episode -> {
                    LocalDate airDate = parseTmdbDate(episode.airDate());
                    return airDate != null && !airDate.isAfter(cutoff);
                })
                .map(TmdbEpisodeSummary::episodeNumber)
                .distinct()
                .sorted()
                .toList();
    }

    private List<Integer> bulkEpisodeNumbers(TmdbSeasonFullDetails season, int finaleEpisodeNumber, LocalDate cutoff) {
        List<Integer> airedEpisodeNumbers = airedEpisodeNumbers(season, cutoff);
        if (airedEpisodeNumbers.isEmpty()) {
            return IntStream.rangeClosed(1, finaleEpisodeNumber).boxed().toList();
        }
        return airedEpisodeNumbers.stream()
                .filter(episodeNumber -> episodeNumber <= finaleEpisodeNumber)
                .toList();
    }

    private boolean hasParseableEpisodeAirDates(TmdbSeasonFullDetails season) {
        return season.episodes() != null && season.episodes().stream()
                .map(TmdbEpisodeSummary::airDate)
                .map(this::parseTmdbDate)
                .anyMatch(Objects::nonNull);
    }

    private boolean isEpisodeReleasedByCutoff(TmdbSeasonFullDetails season, Integer episodeNumber, LocalDate cutoff) {
        if (episodeNumber == null) {
            return false;
        }
        LocalDate airDate = episodeAirDate(season, episodeNumber);
        return airDate != null && !airDate.isAfter(cutoff);
    }

    private LocalDate parseTmdbDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private TmdbUnavailableException tmdbUnavailable() {
        return new TmdbUnavailableException("TMDB is currently unavailable");
    }

    private int resolveSeasonFinaleEpisodeNumber(String seriesTmdbId, Integer seasonNumber, Integer explicitFinaleEpisodeNumber,
            TmdbSeasonFullDetails seasonDetails, LocalDate cutoff) {
        Optional<Content> existingFinale = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(seriesTmdbId, seasonNumber, ContentType.EPISODE);
        boolean hasParseableEpisodeDates = hasParseableEpisodeAirDates(seasonDetails);
        if (existingFinale.isPresent()) {
            Integer existingEpisodeNumber = existingFinale.get().getEpisodeNumber();
            LocalDate existingAirDate = episodeAirDate(seasonDetails, existingEpisodeNumber);
            if (existingEpisodeNumber != null
                    && (isEpisodeReleasedByCutoff(seasonDetails, existingEpisodeNumber, cutoff)
                    || (!hasParseableEpisodeDates && existingAirDate == null))) {
                return existingEpisodeNumber;
            }
        }

        List<Integer> airedEpisodeNumbers = airedEpisodeNumbers(seasonDetails, cutoff);
        if (!airedEpisodeNumbers.isEmpty()) {
            return airedEpisodeNumbers.get(airedEpisodeNumbers.size() - 1);
        }
        if (hasParseableEpisodeDates) {
            throw new BadRequestException("No episodes in season " + seasonNumber + " have been released by " + cutoff);
        }

        if (explicitFinaleEpisodeNumber == null) {
            throw new BadRequestException("finaleEpisodeNumber is required when season " + seasonNumber + " has no known finale episode yet");
        }
        if (explicitFinaleEpisodeNumber < 1) {
            throw new BadRequestException("finaleEpisodeNumber for season " + seasonNumber + " must be greater than or equal to 1");
        }
        int realSeasonEpisodeCount = realSeasonEpisodeCount(seasonDetails);
        if (realSeasonEpisodeCount > 0 && explicitFinaleEpisodeNumber > realSeasonEpisodeCount) {
            throw new BadRequestException("finaleEpisodeNumber for season " + seasonNumber + " exceeds the " + realSeasonEpisodeCount
                    + " episodes known by TMDB for this season");
        }
        return explicitFinaleEpisodeNumber;
    }

    private DiaryEntry bulkLogEpisode(UUID userId, String seriesTmdbId, Integer seasonNumber, int episodeNumber,
            boolean isSeasonFinale, Boolean isSeriesFinale, LocalDate watchedDate, List<DiaryEntry> created,
            ContentType requestedType, List<UUID> companionIds, Integer runtimeMinutes, boolean trustedRuntimeMinutes,
            BulkPassPlan passPlan, BulkCompletionBoundary completionBoundary) {
        EpisodeKey episodeKey = new EpisodeKey(seasonNumber, episodeNumber);
        if (!passPlan.needsEpisode(episodeKey) && !isSeasonFinale) {
            return null;
        }
        boolean matchesKnownGlobalSeasonFinale = !isSeasonFinale || contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(seriesTmdbId, seasonNumber, ContentType.EPISODE)
                .map(Content::getEpisodeNumber)
                .map(existingEpisodeNumber -> existingEpisodeNumber == episodeNumber)
                .orElse(true);
        boolean matchesKnownGlobalSeriesFinale = !Boolean.TRUE.equals(isSeriesFinale) || contentRepository
                .findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(seriesTmdbId, ContentType.SEASON)
                .map(Content::getSeasonNumber)
                .map(existingSeasonNumber -> existingSeasonNumber == seasonNumber)
                .orElse(true);
        Boolean seasonFinaleFlag = isSeasonFinale && matchesKnownGlobalSeasonFinale ? Boolean.TRUE : null;
        Boolean seriesFinaleFlag = isSeasonFinale && matchesKnownGlobalSeasonFinale && matchesKnownGlobalSeriesFinale
                && Boolean.TRUE.equals(isSeriesFinale)
                ? Boolean.TRUE : null;
        ContentRefDTO episodeRef = contentService.getOrCreateReference(new ContentRefCreationDTO(
                null, ContentType.EPISODE, seriesTmdbId, seasonNumber, episodeNumber,
                seasonFinaleFlag, seriesFinaleFlag, runtimeMinutes, null), trustedRuntimeMinutes);

        User user = userRepository.getReferenceById(userId);
        Content episodeContent = contentRepository.getReferenceById(episodeRef.id());
        if (isSeasonFinale) {
            entityManager.refresh(episodeContent);
        }

        if (!passPlan.needsEpisode(episodeKey)) {
            CompletionSignal completion = triggerBulkCompletionCascade(userId, episodeContent, watchedDate, requestedType,
                    completionBoundary, seriesFinaleFlag);
            if (completion.completedSeason() != null) {
                created.add(completion.completedSeason());
            }
            if (completion.completedSeries() != null) {
                created.add(completion.completedSeries());
            }
            return null;
        }

        DiaryEntry entry;
        try {
            entry = persistDiaryEntry(user, episodeContent, null, null, watchedDate, null, null, null, false,
                    isBelowRequestedLevel(ContentType.EPISODE, requestedType), passPlan.targetWatchNumber());
        } catch (DataIntegrityViolationException e) {
            throw mapWatchNumberConflict(e);
        }
        saveCompanions(entry, companionIds);

        CompletionSignal completion = isSeasonFinale
                ? triggerBulkCompletionCascade(userId, episodeContent, watchedDate, requestedType, completionBoundary,
                        seriesFinaleFlag)
                : CompletionSignal.NONE;
        if (completion.completedSeason() != null) {
            created.add(completion.completedSeason());
        }
        if (completion.completedSeries() != null) {
            created.add(completion.completedSeries());
        }

        return entry;
    }

    private void bulkLogSeries(UUID userId, String seriesTmdbId, Integer explicitFinaleSeasonNumber,
            Map<Integer, Integer> seasonFinaleEpisodeNumbers, LocalDate watchedDate, LocalDate cutoff, List<DiaryEntry> created,
            List<UUID> companionIds, String language) {
        int finaleSeasonNumber = resolveSeriesFinaleSeasonNumber(seriesTmdbId, explicitFinaleSeasonNumber, cutoff, language);

        Map<Integer, TmdbSeasonFullDetails> seasonDetailsByNumber = new LinkedHashMap<>();
        Map<Integer, Integer> finaleEpisodeNumbersBySeason = new LinkedHashMap<>();
        Map<Integer, List<Integer>> episodeNumbersBySeason = new LinkedHashMap<>();
        int totalEpisodes = 0;
        for (int seasonNumber = 1; seasonNumber <= finaleSeasonNumber; seasonNumber++) {
            TmdbSeasonFullDetails seasonDetails = fetchSeasonDetails(seriesTmdbId, seasonNumber, language);
            seasonDetailsByNumber.put(seasonNumber, seasonDetails);
            int finaleEpisodeNumber = resolveSeasonFinaleEpisodeNumber(seriesTmdbId, seasonNumber,
                    explicitFinaleEpisodeNumberFor(seasonFinaleEpisodeNumbers, seasonNumber), seasonDetails, cutoff);
            finaleEpisodeNumbersBySeason.put(seasonNumber, finaleEpisodeNumber);
            List<Integer> episodeNumbers = bulkEpisodeNumbers(seasonDetails, finaleEpisodeNumber, cutoff);
            episodeNumbersBySeason.put(seasonNumber, episodeNumbers);
            totalEpisodes += episodeNumbers.size();
        }
        if (totalEpisodes > MAX_BULK_EPISODES) {
            TmdbTvFullDetails series = tmdbClient.getTvFullDetails(seriesTmdbId, language).toOptional().orElseThrow(this::tmdbUnavailable);
            Integer realEpisodeCount = series.numberOfEpisodes();
            if (realEpisodeCount == null || totalEpisodes > realEpisodeCount) {
                throw new BadRequestException("Series has more than " + MAX_BULK_EPISODES
                        + " episodes, exceeding the bulk log limit, and the requested episode count could not be verified against TMDB");
            }
        }

        contentService.getOrCreateReference(new ContentRefCreationDTO(
                seriesTmdbId, ContentType.SERIES, null, null, null, null, null));

        List<EpisodeKey> eligibleEpisodes = episodeNumbersBySeason.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(episodeNumber -> new EpisodeKey(entry.getKey(), episodeNumber)))
                .toList();
        BulkPassPlan passPlan = buildBulkPassPlan(
                eligibleEpisodes,
                diaryEntryRepository.findEpisodeEntriesBySeriesForUser(userId, seriesTmdbId));

        for (int seasonNumber = 1; seasonNumber <= finaleSeasonNumber; seasonNumber++) {
            boolean isSeriesFinaleSeason = seasonNumber == finaleSeasonNumber;
            TmdbSeasonFullDetails seasonDetails = seasonDetailsByNumber.get(seasonNumber);
            int finaleEpisodeNumber = finaleEpisodeNumbersBySeason.get(seasonNumber);
            Map<Integer, Integer> episodeRuntimeMinutes = episodeRuntimeMinutesFromTmdb(seasonDetails);
            for (int episodeNumber : episodeNumbersBySeason.get(seasonNumber)) {
                DiaryEntry entry = bulkLogEpisode(userId, seriesTmdbId, seasonNumber, episodeNumber,
                        episodeNumber == finaleEpisodeNumber, isSeriesFinaleSeason, watchedDate, created,
                        ContentType.SERIES, companionIds, episodeRuntimeMinutes.get(episodeNumber), true, passPlan,
                        new BulkCompletionBoundary(episodeNumbersBySeason.get(seasonNumber), finaleSeasonNumber,
                                passPlan.targetWatchNumber()));
                if (entry != null) {
                    created.add(entry);
                }
            }
        }
    }

    private Integer explicitFinaleEpisodeNumberFor(Map<Integer, Integer> seasonFinaleEpisodeNumbers, int seasonNumber) {
        return seasonFinaleEpisodeNumbers == null ? null : seasonFinaleEpisodeNumbers.get(seasonNumber);
    }

    private int resolveSeriesFinaleSeasonNumber(String seriesTmdbId, Integer explicitFinaleSeasonNumber, LocalDate cutoff, String language) {
        Optional<TmdbTvFullDetails> series = tmdbClient.getTvFullDetails(seriesTmdbId, language).toOptional();
        Optional<Content> existingFinale = contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(seriesTmdbId, ContentType.SEASON);
        boolean hasParseableSeasonDates = series.map(TmdbTvFullDetails::seasons)
                .map(this::hasParseableSeasonAirDates)
                .orElse(false);
        if (existingFinale.isPresent()
                && (series.isEmpty()
                || isSeasonReleasedByCutoff(series.get().seasons(), existingFinale.get().getSeasonNumber(), cutoff)
                || !hasParseableSeasonDates)) {
            Integer existingSeasonNumber = existingFinale.get().getSeasonNumber();
            if (existingSeasonNumber != null) {
                return existingSeasonNumber;
            }
        }

        if (series.isPresent()) {
            int latestAiredSeasonNumber = latestAiredSeasonNumber(series.get().seasons(), cutoff);
            if (latestAiredSeasonNumber >= 1) {
                return latestAiredSeasonNumber;
            }
            if (hasParseableSeasonDates) {
                throw new BadRequestException("No seasons in the series have been released by " + cutoff);
            }
        }

        if (explicitFinaleSeasonNumber == null) {
            if (series.isEmpty()) {
                throw tmdbUnavailable();
            }
            throw new BadRequestException("finaleSeasonNumber is required when the series has no known finale season yet");
        }
        int realSeasonCount = series.map(TmdbTvFullDetails::seasons).map(this::realSeasonCount).orElse(0);
        if (realSeasonCount > 0 && explicitFinaleSeasonNumber > realSeasonCount) {
            throw new BadRequestException("finaleSeasonNumber exceeds the " + realSeasonCount + " seasons known by TMDB for this series");
        }
        return explicitFinaleSeasonNumber;
    }

    private LocalDate seasonAirDate(List<TmdbSeasonSummary> seasons, Integer seasonNumber) {
        if (seasons == null || seasonNumber == null) {
            return null;
        }
        return seasons.stream()
                .filter(season -> Objects.equals(seasonNumber, season.seasonNumber()))
                .map(TmdbSeasonSummary::airDate)
                .map(this::parseTmdbDate)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private boolean isSeasonReleasedByCutoff(List<TmdbSeasonSummary> seasons, Integer seasonNumber, LocalDate cutoff) {
        LocalDate airDate = seasonAirDate(seasons, seasonNumber);
        return airDate != null && !airDate.isAfter(cutoff);
    }

    private boolean hasParseableSeasonAirDates(List<TmdbSeasonSummary> seasons) {
        return seasons != null && seasons.stream()
                .map(TmdbSeasonSummary::airDate)
                .map(this::parseTmdbDate)
                .anyMatch(Objects::nonNull);
    }

    private int latestAiredSeasonNumber(List<TmdbSeasonSummary> seasons, LocalDate cutoff) {
        if (seasons == null) {
            return 0;
        }
        return seasons.stream()
                .filter(season -> season.seasonNumber() != null && season.seasonNumber() > 0)
                .filter(season -> {
                    LocalDate airDate = parseTmdbDate(season.airDate());
                    return airDate != null && !airDate.isAfter(cutoff);
                })
                .map(TmdbSeasonSummary::seasonNumber)
                .max(Integer::compareTo)
                .orElse(0);
    }

    private int realSeasonCount(List<TmdbSeasonSummary> seasons) {
        if (seasons == null) {
            return 0;
        }
        return (int) seasons.stream()
                .filter(season -> season.seasonNumber() != null && season.seasonNumber() > 0)
                .count();
    }

    private DiaryEntry findOwnedEntry(UUID userId, UUID diaryEntryId) {
        DiaryEntry entry = diaryEntryRepository.findById(diaryEntryId)
                .orElseThrow(() -> new NotFoundException("Diary entry not found"));

        if (!entry.getUser().getId().equals(userId)) {
            throw new NotFoundException("Diary entry not found");
        }

        return entry;
    }

    private record CompletionSignal(DiaryEntry completedSeason, DiaryEntry completedSeries) {
        static final CompletionSignal NONE = new CompletionSignal(null, null);
    }

    private static final Map<ContentType, Integer> HIERARCHY_LEVEL = Map.of(
            ContentType.EPISODE, 0,
            ContentType.SEASON, 1,
            ContentType.SERIES, 2);

    private boolean isBelowRequestedLevel(ContentType entryType, ContentType requestedType) {
        Integer entryLevel = HIERARCHY_LEVEL.get(entryType);
        Integer requestedLevel = HIERARCHY_LEVEL.get(requestedType);
        if (entryLevel == null || requestedLevel == null) {
            return false;
        }
        return entryLevel < requestedLevel;
    }

    private CompletionSignal triggerCompletionCascade(UUID userId, Content loggedContent, LocalDate watchedDate, ContentType requestedType) {
        if (loggedContent.getType() == ContentType.EPISODE) {
            DiaryEntry completedSeason = maybeCompleteSeason(userId, loggedContent.getSeriesTmdbId(), loggedContent.getSeasonNumber(), watchedDate, requestedType);
            if (completedSeason == null) {
                return CompletionSignal.NONE;
            }
            CompletionSignal seriesSignal = triggerCompletionCascade(userId, completedSeason.getContent(), completedSeason.getWatchedDate(), requestedType);
            return new CompletionSignal(completedSeason, seriesSignal.completedSeries());
        }
        if (loggedContent.getType() == ContentType.SEASON) {
            return new CompletionSignal(null, maybeCompleteSeries(userId, loggedContent.getSeriesTmdbId(), watchedDate, requestedType));
        }
        return CompletionSignal.NONE;
    }

    private CompletionSignal triggerBulkCompletionCascade(UUID userId, Content loggedContent, LocalDate watchedDate,
            ContentType requestedType, BulkCompletionBoundary boundary, Boolean globalSeriesFinale) {
        if (loggedContent.getType() != ContentType.EPISODE) {
            return CompletionSignal.NONE;
        }
        String seriesTmdbId = loggedContent.getSeriesTmdbId();
        List<DiaryEntry> episodeHistory = diaryEntryRepository.findEpisodeEntriesByUserIdAndSeriesTmdbIdAndSeasonNumber(
                userId, seriesTmdbId, loggedContent.getSeasonNumber());
        DiaryEntry completedSeason = completeSeasonPass(userId, seriesTmdbId, loggedContent.getSeasonNumber(),
                boundary.eligibleEpisodes(), boundary.targetWatchNumber(), globalSeriesFinale, watchedDate,
                requestedType, episodeHistory);
        if (boundary.seriesFinaleSeasonNumber() == null) {
            DiaryEntry completedSeries = completedSeason == null ? null
                    : maybeCompleteSeries(userId, seriesTmdbId, watchedDate, requestedType);
            return new CompletionSignal(completedSeason, completedSeries);
        }
        List<DiaryEntry> seasonHistory = diaryEntryRepository.findAllSeasonEntriesInSeries(userId, seriesTmdbId);
        DiaryEntry completedSeries = completeSeriesPass(userId, seriesTmdbId, boundary.seriesFinaleSeasonNumber(),
                boundary.targetWatchNumber(), watchedDate, requestedType, seasonHistory);
        return new CompletionSignal(completedSeason, completedSeries);
    }

    private DiaryEntry persistAutoGeneratedEntry(UUID userId, User user, Content content, LocalDate watchedDate,
            int expectedWatchNumber, boolean ignore, List<UUID> unanimousCompanionIds) {
        try {
            return newTransactionExecutor.runInNewTransaction(() -> {
                DiaryEntry entry = persistDiaryEntry(user, content, null, null, watchedDate, null, null, null, true, ignore,
                        expectedWatchNumber);
                saveCompanions(entry, unanimousCompanionIds);
                return entry;
            });
        } catch (DataIntegrityViolationException e) {
            return diaryEntryRepository
                    .findFirstByUserIdAndContentIdAndWatchNumber(userId, content.getId(), expectedWatchNumber)
                    .orElseThrow(() -> e);
        }
    }

    private List<UUID> computeUnanimousCompanions(List<UUID> childEntryIds) {
        if (childEntryIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, Set<UUID>> companionsByEntry = watchCompanionRepository.findByDiaryEntryIdIn(childEntryIds).stream()
                .collect(Collectors.groupingBy(wc -> wc.getDiaryEntry().getId(),
                        Collectors.mapping(wc -> wc.getUser().getId(), Collectors.toSet())));

        Set<UUID> unanimous = null;
        for (UUID childEntryId : childEntryIds) {
            Set<UUID> companions = companionsByEntry.getOrDefault(childEntryId, Set.of());
            if (companions.isEmpty()) {
                return List.of();
            }
            if (unanimous == null) {
                unanimous = companions;
            } else if (!unanimous.equals(companions)) {
                return List.of();
            }
        }
        return List.copyOf(unanimous);
    }

    private DiaryEntry maybeCompleteSeason(UUID userId, String seriesTmdbId, Integer seasonNumber, LocalDate watchedDate, ContentType requestedType) {
        Optional<Content> seasonFinaleEpisode = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(seriesTmdbId, seasonNumber, ContentType.EPISODE);
        if (seasonFinaleEpisode.isEmpty()) {
            return null;
        }
        int finaleEpisodeNumber = seasonFinaleEpisode.get().getEpisodeNumber();
        List<Integer> eligibleEpisodes = IntStream.rangeClosed(1, finaleEpisodeNumber).boxed().toList();
        List<DiaryEntry> history = diaryEntryRepository.findEpisodeEntriesByUserIdAndSeriesTmdbIdAndSeasonNumber(
                userId, seriesTmdbId, seasonNumber);
        DiaryEntry lastCreated = null;
        for (int pass : positiveWatchNumbers(history)) {
            DiaryEntry created = completeSeasonPass(userId, seriesTmdbId, seasonNumber, eligibleEpisodes, pass,
                    seasonFinaleEpisode.get().getIsSeriesFinale(), watchedDate, requestedType, history);
            if (created != null) {
                lastCreated = created;
            }
        }
        return lastCreated;
    }

    private DiaryEntry maybeCompleteSeason(UUID userId, String seriesTmdbId, Integer seasonNumber, int finaleEpisodeNumber,
            LocalDate watchedDate, ContentType requestedType) {
        List<Integer> eligibleEpisodes = IntStream.rangeClosed(1, finaleEpisodeNumber).boxed().toList();
        List<DiaryEntry> history = diaryEntryRepository.findEpisodeEntriesByUserIdAndSeriesTmdbIdAndSeasonNumber(
                userId, seriesTmdbId, seasonNumber);
        DiaryEntry lastCreated = null;
        for (int pass : positiveWatchNumbers(history)) {
            DiaryEntry created = completeSeasonPass(userId, seriesTmdbId, seasonNumber, eligibleEpisodes, pass,
                    null, watchedDate, requestedType, history);
            if (created != null) {
                lastCreated = created;
            }
        }
        return lastCreated;
    }

    private List<Integer> positiveWatchNumbers(List<DiaryEntry> entries) {
        return entries.stream().map(DiaryEntry::getWatchNumber).filter(number -> number != null && number > 0)
                .distinct().sorted().toList();
    }

    private DiaryEntry completeSeasonPass(UUID userId, String seriesTmdbId, int seasonNumber,
            List<Integer> eligibleEpisodes, int pass, Boolean globalSeriesFinale, LocalDate watchedDate,
            ContentType requestedType, List<DiaryEntry> history) {
        Set<Integer> eligible = Set.copyOf(eligibleEpisodes);
        List<DiaryEntry> children = history.stream()
                .filter(entry -> Objects.equals(entry.getWatchNumber(), pass))
                .filter(entry -> eligible.contains(entry.getContent().getEpisodeNumber()))
                .toList();
        Set<Integer> present = children.stream().map(entry -> entry.getContent().getEpisodeNumber()).collect(Collectors.toSet());
        if (!present.containsAll(eligible)) {
            return null;
        }
        ContentRefDTO seasonRef = contentService.getOrCreateReference(new ContentRefCreationDTO(
                null, ContentType.SEASON, seriesTmdbId, seasonNumber, null, null, globalSeriesFinale));
        if (diaryEntryRepository.findFirstByUserIdAndContentIdAndWatchNumber(userId, seasonRef.id(), pass).isPresent()) {
            return null;
        }
        User user = userRepository.getReferenceById(userId);
        Content seasonContent = contentRepository.getReferenceById(seasonRef.id());
        return persistAutoGeneratedEntry(userId, user, seasonContent, watchedDate, pass,
                isBelowRequestedLevel(ContentType.SEASON, requestedType),
                computeUnanimousCompanions(children.stream().map(DiaryEntry::getId).toList()));
    }

    private int minEpisodeWatchCount(UUID userId, String seriesTmdbId, Integer seasonNumber, int finaleEpisodeNumber) {
        if (finaleEpisodeNumber < 1) {
            return 0;
        }

        Map<Integer, Long> countsByEpisode = diaryEntryRepository
                .countEntriesByEpisodeNumberInSeason(userId, seriesTmdbId, seasonNumber).stream()
                .collect(Collectors.toMap(DiaryEntryRepository.EpisodeWatchCount::getEpisodeNumber, DiaryEntryRepository.EpisodeWatchCount::getCount));

        int minCount = Integer.MAX_VALUE;
        for (int episode = 1; episode <= finaleEpisodeNumber; episode++) {
            minCount = Math.min(minCount, countsByEpisode.getOrDefault(episode, 0L).intValue());
            if (minCount == 0) {
                break;
            }
        }
        return minCount;
    }

    private DiaryEntry maybeCompleteSeries(UUID userId, String seriesTmdbId, LocalDate watchedDate, ContentType requestedType) {
        Optional<Content> seriesFinaleSeason = contentRepository
                .findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(seriesTmdbId, ContentType.SEASON);
        if (seriesFinaleSeason.isEmpty()) {
            return null;
        }
        return completeSeriesPasses(userId, seriesTmdbId, seriesFinaleSeason.get().getSeasonNumber(), watchedDate, requestedType);
    }

    private DiaryEntry maybeCompleteSeries(UUID userId, String seriesTmdbId, int finaleSeasonNumber,
            LocalDate watchedDate, ContentType requestedType) {
        return completeSeriesPasses(userId, seriesTmdbId, finaleSeasonNumber, watchedDate, requestedType);
    }

    private DiaryEntry completeSeriesPasses(UUID userId, String seriesTmdbId, int finaleSeasonNumber,
            LocalDate watchedDate, ContentType requestedType) {
        List<DiaryEntry> history = diaryEntryRepository.findAllSeasonEntriesInSeries(userId, seriesTmdbId);
        DiaryEntry lastCreated = null;
        for (int pass : positiveWatchNumbers(history)) {
            DiaryEntry created = completeSeriesPass(userId, seriesTmdbId, finaleSeasonNumber, pass,
                    watchedDate, requestedType, history);
            if (created != null) {
                lastCreated = created;
            }
        }
        return lastCreated;
    }

    private DiaryEntry completeSeriesPass(UUID userId, String seriesTmdbId, int finaleSeasonNumber, int pass,
            LocalDate watchedDate, ContentType requestedType, List<DiaryEntry> history) {
        List<DiaryEntry> children = history.stream()
                .filter(entry -> Objects.equals(entry.getWatchNumber(), pass))
                .filter(entry -> entry.getContent().getSeasonNumber() >= 1
                        && entry.getContent().getSeasonNumber() <= finaleSeasonNumber)
                .toList();
        Set<Integer> present = children.stream().map(entry -> entry.getContent().getSeasonNumber()).collect(Collectors.toSet());
        if (present.size() != finaleSeasonNumber) {
            return null;
        }
        ContentRefDTO seriesRef = contentService.getOrCreateReference(new ContentRefCreationDTO(
                seriesTmdbId, ContentType.SERIES, null, null, null, null, null));
        if (diaryEntryRepository.findFirstByUserIdAndContentIdAndWatchNumber(userId, seriesRef.id(), pass).isPresent()) {
            return null;
        }
        User user = userRepository.getReferenceById(userId);
        Content seriesContent = contentRepository.getReferenceById(seriesRef.id());
        return persistAutoGeneratedEntry(userId, user, seriesContent, watchedDate, pass,
                isBelowRequestedLevel(ContentType.SERIES, requestedType),
                computeUnanimousCompanions(children.stream().map(DiaryEntry::getId).toList()));
    }

    private int minSeasonWatchMax(UUID userId, String seriesTmdbId, int finaleSeasonNumber) {
        if (finaleSeasonNumber < 1) {
            return 0;
        }

        Map<Integer, Integer> maxBySeason = diaryEntryRepository
                .maxWatchNumberBySeasonInSeries(userId, seriesTmdbId).stream()
                .collect(Collectors.toMap(DiaryEntryRepository.SeasonWatchMax::getSeasonNumber, DiaryEntryRepository.SeasonWatchMax::getMaxWatchNumber));

        int minMax = Integer.MAX_VALUE;
        for (int season = 1; season <= finaleSeasonNumber; season++) {
            minMax = Math.min(minMax, maxBySeason.getOrDefault(season, 0));
            if (minMax == 0) {
                break;
            }
        }
        return minMax;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeletionImpactDTO computeDeletionImpact(UUID userId, UUID diaryEntryId, boolean overrideProtectedEntries) {
        List<DiaryEntry> cascadeDeleted = new ArrayList<>();

        deleteDiaryEntry(userId, diaryEntryId, overrideProtectedEntries, cascadeDeleted);

        markCurrentTransactionRollbackOnly();

        return new DeletionImpactDTO(cascadeDeleted.stream()
                .map(candidate -> new DeletionImpactItemDTO(
                        candidate.getId(),
                        candidate.getContent().getType(),
                        candidate.getWatchedDate(),
                        candidate.getWatchNumber(),
                        Boolean.TRUE.equals(candidate.getAutoGenerated()),
                        candidate.getComment() != null || candidate.getScore() != null))
                .toList());
    }

    private void markCurrentTransactionRollbackOnly() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }
}
