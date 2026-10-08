package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.comment.service.impl.CommentPreviewAssembler;
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
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.diaryentry.dto.DeletionImpactDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DeletionImpactItemDTO;
import com.watchwise.watchwise_api.diaryentry.dto.ContentReviewResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.ContentReviewSource;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryBulkCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryDaySummaryDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationResultDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryUpdateDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiarySeriesOptionDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeasonProgressDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressAggregateDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressPageResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.entity.WatchCompanion;
import com.watchwise.watchwise_api.diaryentry.mapper.DiaryEntryMapper;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryReadRepository;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntrySearchCriteria;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntrySort;
import com.watchwise.watchwise_api.diaryentry.repository.ContentReviewSort;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchedEpisodeCoordinate;
import com.watchwise.watchwise_api.diaryentry.service.DiaryEntryService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressReadRepository;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressAssembler;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataRefreshService;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressPresentationEnricher;
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
import org.springframework.data.domain.PageImpl;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
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
    private final DiaryEntryReadRepository diaryEntryReadRepository;
    private final UserRepository userRepository;
    private final ContentRepository contentRepository;
    private final ContentService contentService;
    private final ContentMapper contentMapper;
    private final ContentCardAssembler contentCardAssembler;
    private final UserContentPosterService userContentPosterService;
    private final FollowerRepository followerRepository;
    private final DiaryEntryMapper diaryEntryMapper;
    private final UserMapper userMapper;
    private final NewTransactionExecutor newTransactionExecutor;
    private final WatchlistEntryService watchlistEntryService;
    private final DroppedEntryRepository droppedEntryRepository;
    private final LikeService likeService;
    private final CommentPreviewAssembler commentPreviewAssembler;
    private final WatchCompanionRepository watchCompanionRepository;
    private final PageRequestFactory pageRequestFactory;
    private final TmdbClient tmdbClient;
    private final SeriesProgressReadRepository seriesProgressReadRepository;
    private final SeriesProgressMetadataRefreshService seriesProgressMetadataRefreshService;
    private final SeriesProgressAssembler seriesProgressAssembler;
    private final SeriesProgressPresentationEnricher seriesProgressPresentationEnricher;
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<DiaryEntryResponseDTO> getDiaryEntries(UUID viewerId, UUID userId, Integer year, Integer pageNumber, Integer pageSize,
            ContentType type, LocalDate dateFrom, LocalDate dateTo, Boolean hasReview, String seriesTmdbId, Integer score) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        assertCanViewDiary(viewerId, userId, target);

        String normalizedSeriesTmdbId = seriesTmdbId == null ? null : seriesTmdbId.trim();
        if (seriesTmdbId != null && normalizedSeriesTmdbId.isEmpty()) {
            throw new BadRequestException("seriesTmdbId cannot be blank");
        }
        if (score != null && (score < 1 || score > 10)) {
            throw new BadRequestException("score must be between 1 and 10");
        }

        if (year != null && (dateFrom != null || dateTo != null)) {
            throw new BadRequestException("year cannot be combined with dateFrom/dateTo");
        }

        LocalDate effectiveDateFrom = dateFrom != null ? dateFrom : (year != null ? startOfYear(year) : null);
        LocalDate effectiveDateTo = dateTo != null ? dateTo : (year != null ? endOfYear(year) : null);
        boolean hasExtraFilters = type != null || effectiveDateFrom != null || effectiveDateTo != null
                || hasReview != null || normalizedSeriesTmdbId != null || score != null;

        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);

        Page<DiaryEntry> entries = hasExtraFilters
                ? diaryEntryRepository.findByUserIdWithFilters(
                        userId, type, effectiveDateFrom, effectiveDateTo, hasReview, normalizedSeriesTmdbId, score, pageRequest)
                : diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(userId, pageRequest);

        return enrichDiaryPage(viewerId, userId, target, entries);
    }

    @Override
    public Page<DiaryEntryResponseDTO> getDiaryEntries(
            UUID viewerId, UUID userId, Integer year, Integer pageNumber, Integer pageSize,
            ContentType type, LocalDate dateFrom, LocalDate dateTo, Boolean hasReview,
            String seriesTmdbId, Integer score, Integer scoreFrom, Integer scoreTo, DiaryEntrySort sortBy) {
        DiaryEntrySort effectiveSort = sortBy == null ? DiaryEntrySort.NEWEST : sortBy;
        validateScoreRange(score, scoreFrom, scoreTo);
        if (effectiveSort == DiaryEntrySort.NEWEST && scoreFrom == null && scoreTo == null) {
            return getDiaryEntries(viewerId, userId, year, pageNumber, pageSize, type, dateFrom, dateTo,
                    hasReview, seriesTmdbId, score);
        }

        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewDiary(viewerId, userId, target);

        String normalizedSeriesTmdbId = seriesTmdbId == null ? null : seriesTmdbId.trim();
        if (seriesTmdbId != null && normalizedSeriesTmdbId.isEmpty()) {
            throw new BadRequestException("seriesTmdbId cannot be blank");
        }
        if (year != null && (dateFrom != null || dateTo != null)) {
            throw new BadRequestException("year cannot be combined with dateFrom/dateTo");
        }

        LocalDate effectiveDateFrom = dateFrom != null ? dateFrom : (year != null ? startOfYear(year) : null);
        LocalDate effectiveDateTo = dateTo != null ? dateTo : (year != null ? endOfYear(year) : null);
        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);
        Page<DiaryEntry> entries = diaryEntryReadRepository.findPage(
                new DiaryEntrySearchCriteria(userId, type, effectiveDateFrom, effectiveDateTo, hasReview,
                        normalizedSeriesTmdbId, score, scoreFrom, scoreTo),
                effectiveSort, pageRequest);

        return enrichDiaryPage(viewerId, userId, target, entries);
    }

    private void validateScoreRange(Integer score, Integer scoreFrom, Integer scoreTo) {
        if (scoreFrom != null && (scoreFrom < 1 || scoreFrom > 10)
                || scoreTo != null && (scoreTo < 1 || scoreTo > 10)) {
            throw new BadRequestException("scoreFrom and scoreTo must be between 1 and 10");
        }
        if (score != null && (scoreFrom != null || scoreTo != null)) {
            throw new BadRequestException("score cannot be combined with scoreFrom/scoreTo");
        }
        if (scoreFrom != null && scoreTo != null && scoreFrom > scoreTo) {
            throw new BadRequestException("scoreFrom cannot be greater than scoreTo");
        }
    }

    private Page<DiaryEntryResponseDTO> enrichDiaryPage(
            UUID viewerId, UUID userId, User target, Page<DiaryEntry> entries) {
        List<UUID> entryIds = entries.getContent().stream().map(DiaryEntry::getId).toList();
        Set<UUID> likedEntryIds = likeService.getLikedDiaryEntryIds(viewerId, entryIds);
        Map<UUID, Long> commentCountsByEntryId = Optional.ofNullable(commentPreviewAssembler.countDiaryEntries(entryIds))
                .orElseGet(Map::of);
        Map<UUID, List<UserPreviewDTO>> watchedWithByEntryId = loadWatchedWith(entryIds);
        Map<ContentCoordinate, ContentCardDTO> cardsByCoordinate = assembleDiaryCards(viewerId, target, entries.getContent());
        List<DiaryEntry> entriesWithoutCard = entries.getContent().stream()
                .filter(entry -> cardFor(entry.getContent(), cardsByCoordinate) == null)
                .toList();
        Map<UUID, String> fallbackPostersByContentId = loadPostersForOwner(userId, entriesWithoutCard);

        return entries.map(entry -> enrichDiaryEntryResponse(
                entry,
                likedEntryIds.contains(entry.getId()),
                commentCountsByEntryId.getOrDefault(entry.getId(), 0L),
                watchedWithByEntryId.getOrDefault(entry.getId(), List.of()),
                customPosterUrl(entry, cardsByCoordinate, fallbackPostersByContentId),
                cardFor(entry.getContent(), cardsByCoordinate)));
    }

    @Override
    public DiaryEntryResponseDTO getDiaryEntry(UUID viewerId, UUID diaryEntryId) {
        DiaryEntry entry = diaryEntryRepository.findByIdWithContentAndUser(diaryEntryId)
                .orElseThrow(() -> new NotFoundException("Diary entry not found"));

        User owner = entry.getUser();
        assertCanViewDiary(viewerId, owner.getId(), owner);

        Set<UUID> likedEntryIds = likeService.getLikedDiaryEntryIds(viewerId, List.of(diaryEntryId));
        long commentsCount = Optional.ofNullable(commentPreviewAssembler.countDiaryEntries(List.of(diaryEntryId)))
                .orElseGet(Map::of)
                .getOrDefault(diaryEntryId, 0L);
        List<UserPreviewDTO> watchedWith = loadWatchedWith(List.of(diaryEntryId))
                .getOrDefault(diaryEntryId, List.of());
        Map<ContentCoordinate, ContentCardDTO> cardsByCoordinate = assembleDiaryCards(viewerId, owner, List.of(entry));
        ContentCardDTO card = cardFor(entry.getContent(), cardsByCoordinate);
        String customPosterUrl = card == null
                ? loadPostersForOwner(owner.getId(), List.of(entry)).get(entry.getContent().getId())
                : card.customPosterUrl();

        return enrichDiaryEntryResponse(entry, likedEntryIds.contains(diaryEntryId), commentsCount,
                watchedWith, customPosterUrl, card);
    }

    @Override
    public List<DiaryDaySummaryDTO> getDiaryDailySummary(
            UUID viewerId, UUID userId, ContentType type, LocalDate dateFrom, LocalDate dateTo,
            Boolean hasReview, String seriesTmdbId, Integer score, Integer scoreFrom, Integer scoreTo) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewDiary(viewerId, userId, target);
        validateScoreRange(score, scoreFrom, scoreTo);

        String normalizedSeriesTmdbId = seriesTmdbId == null ? null : seriesTmdbId.trim();
        if (seriesTmdbId != null && normalizedSeriesTmdbId.isEmpty()) {
            throw new BadRequestException("seriesTmdbId cannot be blank");
        }

        return diaryEntryReadRepository.findDailySummary(new DiaryEntrySearchCriteria(
                        userId, type, dateFrom, dateTo, hasReview, normalizedSeriesTmdbId,
                        score, scoreFrom, scoreTo))
                .stream()
                .map(row -> new DiaryDaySummaryDTO(row.date(), row.plays(), row.totalMinutes()))
                .toList();
    }

    @Override
    public List<DiarySeriesOptionDTO> getDiarySeriesOptions(UUID viewerId, UUID userId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        assertCanViewDiary(viewerId, userId, target);

        Comparator<DiarySeriesOptionDTO> comparator = Comparator
                .comparing(DiarySeriesOptionDTO::entriesCount, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DiarySeriesOptionDTO::title, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(DiarySeriesOptionDTO::seriesTmdbId, Comparator.nullsLast(Comparator.naturalOrder()));

        return diaryEntryRepository.findSeriesEntryCountsByUserId(userId).stream()
                .map(row -> new DiarySeriesOptionDTO(
                        row.getSeriesTmdbId(), resolveSeriesTitle(row.getSeriesTmdbId(), target.getPreferredLanguage()),
                        row.getEntriesCount()))
                .sorted(comparator)
                .toList();
    }

    private String resolveSeriesTitle(String seriesTmdbId, String language) {
        return switch (tmdbClient.getTvFullDetails(seriesTmdbId, language)) {
            case TmdbLookupResult.Found<TmdbTvFullDetails> found -> found.value().name();
            case TmdbLookupResult.NotFound<TmdbTvFullDetails> ignored -> null;
            case TmdbLookupResult.Unavailable<TmdbTvFullDetails> ignored -> throw tmdbUnavailable();
        };
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
        Map<String, String> customPosterBySeries = loadSeriesPosters(userId, seriesTmdbIds);

        return seriesInProgress.map(row -> toSeriesInProgressResponse(
                row,
                watchedEpisodeCountsBySeriesAndSeason.getOrDefault(row.getSeriesTmdbId(), Map.of()),
                customPosterBySeries.get(row.getSeriesTmdbId())));
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
        Map<String, String> customPosterBySeries = loadSeriesPosters(userId, pageSeriesIds);

        Map<String, SeriesInProgressResponseDTO> baseResponses = new LinkedHashMap<>();
        page.getContent().forEach(row -> baseResponses.put(
                row.getSeriesTmdbId(),
                toDetailedSeriesResponse(
                        row,
                        snapshots.get(row.getSeriesTmdbId()),
                        watchedProgress.getOrDefault(row.getSeriesTmdbId(), Map.of()),
                        customPosterBySeries.get(row.getSeriesTmdbId()))));

        Set<WatchedEpisodeCoordinate> watchedCoordinates = pageSeriesIds.isEmpty()
                ? Set.of()
                : Optional.ofNullable(diaryEntryRepository.findWatchedEpisodeCoordinates(userId, pageSeriesIds))
                        .orElseGet(Set::of);
        Map<String, List<SeasonProgressDTO>> seasonProgressBySeries = baseResponses.values().stream()
                .collect(Collectors.toMap(
                        SeriesInProgressResponseDTO::seriesTmdbId,
                        SeriesInProgressResponseDTO::seasonProgress,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        Map<String, SeriesProgressPresentationEnricher.Enrichment> presentation = pageSeriesIds.isEmpty()
                ? Map.of()
                : Optional.ofNullable(seriesProgressPresentationEnricher.enrich(
                                target, page.getContent(), seasonProgressBySeries,
                                watchedCoordinates, customPosterBySeries))
                        .orElseGet(Map::of);

        List<SeriesInProgressResponseDTO> content = page.getContent().stream()
                .map(row -> {
                    SeriesInProgressResponseDTO base = baseResponses.get(row.getSeriesTmdbId());
                    SeriesProgressPresentationEnricher.Enrichment enrichment = presentation.get(row.getSeriesTmdbId());
                    return enrichment == null
                            ? base
                            : base.withPresentation(
                                    enrichment.seriesTitle(), enrichment.seriesPosterPath(),
                                    enrichment.lastWatchedEpisodeTitle(), enrichment.nextEpisode());
                })
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
        List<String> seriesTmdbIds = rows.stream()
                .map(SeriesProgressReadRepository.SeriesProgressCandidate::getSeriesTmdbId)
                .distinct()
                .toList();
        return seriesProgressMetadataRefreshService.getSnapshotsForRead(seriesTmdbIds, today);
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
            Map<Integer, DiaryEntryRepository.SeasonProgress> watchedProgress,
            String customPosterUrl) {
        return seriesProgressAssembler.toDetailedSeriesResponse(
                row, snapshot, watchedProgress, customPosterUrl);
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
            if (!Boolean.TRUE.equals(row.getWatchedRuntimeComplete())) {
                completeRuntime = false;
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
        if (totals != null && !Boolean.TRUE.equals(totals.getWatchedRuntimeComplete())) {
            completeRuntime = false;
        }
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

    private Map<String, String> loadSeriesPosters(UUID userId, List<String> seriesTmdbIds) {
        if (seriesTmdbIds.isEmpty()) {
            return Map.of();
        }
        return Optional.ofNullable(userContentPosterService.findSeriesPosters(userId, seriesTmdbIds))
                .orElseGet(Map::of);
    }

    private SeriesInProgressResponseDTO toSeriesInProgressResponse(
            DiaryEntryRepository.SeriesInProgress row,
            Map<Integer, Long> watchedEpisodeCountsBySeason,
            String customPosterUrl) {
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
                row.getWatchedEpisodeCount(), totalEpisodeCount, watchedPercentage, progress.seasonProgress(),
                null, null, null, totalEpisodeCount, null, null, null, null, customPosterUrl,
                null, null, null, null);
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
    public Page<ContentReviewResponseDTO> getReviewsForContent(UUID viewerId, UUID contentId, Integer pageNumber, Integer pageSize) {
        return getReviewsForContentInternal(viewerId, contentId, null, pageNumber, pageSize);
    }

    @Override
    public Page<ContentReviewResponseDTO> getReviewsForContent(
            UUID viewerId, UUID contentId, ContentReviewSort sort, Integer pageNumber, Integer pageSize) {
        return getReviewsForContentInternal(viewerId, contentId,
                sort == null ? ContentReviewSort.RECENT : sort, pageNumber, pageSize);
    }

    private Page<ContentReviewResponseDTO> getReviewsForContentInternal(
            UUID viewerId, UUID contentId, ContentReviewSort sort, Integer pageNumber, Integer pageSize) {
        if (!contentRepository.existsById(contentId)) {
            throw new NotFoundException("Content not found");
        }

        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);
        Page<DiaryEntryRepository.ContentReviewKey> reviewKeys = sort == null
                ? diaryEntryRepository.findContentReviewKeys(contentId, viewerId, pageRequest)
                : diaryEntryRepository.findContentReviewKeys(contentId, viewerId, sort.name(), pageRequest);

        List<UUID> diaryIds = reviewKeys.getContent().stream()
                .filter(key -> ContentReviewSource.DIARY.name().equals(key.getSource()))
                .map(DiaryEntryRepository.ContentReviewKey::getReviewId)
                .toList();
        List<UUID> droppedIds = reviewKeys.getContent().stream()
                .filter(key -> ContentReviewSource.DROPPED.name().equals(key.getSource()))
                .map(DiaryEntryRepository.ContentReviewKey::getReviewId)
                .toList();

        Map<UUID, DiaryEntry> diaryById = diaryIds.isEmpty()
                ? Map.of()
                : diaryEntryRepository.findByIdInWithContentAndUser(diaryIds).stream()
                        .collect(Collectors.toMap(DiaryEntry::getId, entry -> entry));
        Map<UUID, DroppedEntry> droppedById = droppedIds.isEmpty()
                ? Map.of()
                : droppedEntryRepository.findByIdInWithUserAndContent(droppedIds).stream()
                        .collect(Collectors.toMap(DroppedEntry::getId, entry -> entry));

        Set<UUID> likedDiaryIds = likeService.getLikedDiaryEntryIds(viewerId, diaryIds);
        Set<UUID> likedDroppedIds = likeService.getLikedDroppedEntryIds(viewerId, droppedIds);
        Map<UUID, Long> commentCountsByDiaryId = Optional.ofNullable(commentPreviewAssembler.countDiaryEntries(diaryIds))
                .orElseGet(Map::of);
        Map<UUID, Long> commentCountsByDroppedId = Optional.ofNullable(commentPreviewAssembler.countDroppedEntries(droppedIds))
                .orElseGet(Map::of);
        Map<UUID, List<UserPreviewDTO>> watchedWithByEntryId = loadWatchedWith(diaryIds);
        List<UserContentPosterService.UserContentPosterKey> posterKeys = diaryIds.stream()
                .map(diaryById::get)
                .map(entry -> new UserContentPosterService.UserContentPosterKey(
                        entry.getUser().getId(), entry.getContent().getId()))
                .distinct()
                .toList();
        Map<UserContentPosterService.UserContentPosterKey, String> customPosterByAuthorAndContent = posterKeys.isEmpty()
                ? Map.of()
                : userContentPosterService.findByUserAndContentPairs(posterKeys);

        List<ContentReviewResponseDTO> reviews = reviewKeys.getContent().stream()
                .map(key -> toContentReviewResponse(key, diaryById, droppedById, likedDiaryIds, likedDroppedIds,
                        commentCountsByDiaryId, commentCountsByDroppedId, watchedWithByEntryId,
                        customPosterByAuthorAndContent))
                .toList();
        Map<UUID, ContentCardDTO> cardsByReviewId = assembleReviewCards(viewerId, reviews);
        reviews = reviews.stream()
                .map(review -> review.withCard(cardsByReviewId.get(review.id())))
                .toList();

        return new PageImpl<>(reviews, pageRequest, reviewKeys.getTotalElements());
    }

    private ContentReviewResponseDTO toContentReviewResponse(
            DiaryEntryRepository.ContentReviewKey key,
            Map<UUID, DiaryEntry> diaryById,
            Map<UUID, DroppedEntry> droppedById,
            Set<UUID> likedDiaryIds,
            Set<UUID> likedDroppedIds,
            Map<UUID, Long> commentCountsByDiaryId,
            Map<UUID, Long> commentCountsByDroppedId,
            Map<UUID, List<UserPreviewDTO>> watchedWithByEntryId,
            Map<UserContentPosterService.UserContentPosterKey, String> customPosterByAuthorAndContent) {
        if (ContentReviewSource.DIARY.name().equals(key.getSource())) {
            DiaryEntry entry = diaryById.get(key.getReviewId());
            UserContentPosterService.UserContentPosterKey posterKey = new UserContentPosterService.UserContentPosterKey(
                    entry.getUser().getId(), entry.getContent().getId());
            DiaryEntryResponseDTO diaryResponse = enrichDiaryEntryResponse(
                    entry,
                    likedDiaryIds.contains(entry.getId()),
                    commentCountsByDiaryId.getOrDefault(entry.getId(), 0L),
                    watchedWithByEntryId.getOrDefault(entry.getId(), List.of()),
                    customPosterByAuthorAndContent.get(posterKey));
            return ContentReviewResponseDTO.fromDiary(diaryResponse, userMapper.userToUserPreviewDto(entry.getUser()));
        }

        DroppedEntry entry = droppedById.get(key.getReviewId());
        return new ContentReviewResponseDTO(
                entry.getId(), ContentReviewSource.DROPPED, entry.getUser().getId(),
                userMapper.userToUserPreviewDto(entry.getUser()),
                contentMapper.contentToContentRefDto(entry.getContent()), entry.getComment(), null, null, null, null,
                null, null, null, entry.getCreatedAt(), entry.getUpdatedAt(), entry.getLikesCount(),
                commentCountsByDroppedId.getOrDefault(entry.getId(), 0L),
                likedDroppedIds.contains(entry.getId()), List.of(), null);
    }

    private Map<UUID, ContentCardDTO> assembleReviewCards(
            UUID viewerId, Collection<ContentReviewResponseDTO> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return Map.of();
        }

        User viewer = viewerId == null ? null : userRepository.findById(viewerId).orElse(null);
        String language = viewer == null || viewer.getPreferredLanguage() == null
                ? TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE
                : viewer.getPreferredLanguage();
        String region = viewer == null ? null : viewer.getPreferredRegion();
        Map<UUID, List<ContentReviewResponseDTO>> reviewsByOwner = reviews.stream()
                .filter(review -> review != null && review.id() != null && review.userId() != null)
                .collect(Collectors.groupingBy(
                        ContentReviewResponseDTO::userId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        Map<UUID, ContentCardDTO> cardsByReviewId = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<ContentReviewResponseDTO>> ownerGroup : reviewsByOwner.entrySet()) {
            List<ContentCardSpec> specs = distinctCardSpecs(ownerGroup.getValue().stream()
                    .map(ContentReviewResponseDTO::content)
                    .filter(content -> content != null && content.type() != null)
                    .map(content -> new ContentCardSpec(
                            toCoordinate(content), null, null, null, content.runtimeMinutes()))
                    .toList());
            if (specs.isEmpty()) {
                continue;
            }
            Map<ContentCoordinate, ContentCardDTO> cards = contentCardAssembler.assemble(
                    specs,
                    new ContentCardContext(language, region, ownerGroup.getKey(), viewerId),
                    cardFields(false, viewerId));
            if (cards == null) {
                continue;
            }
            ownerGroup.getValue().forEach(review -> {
                ContentRefDTO content = review.content();
                if (content != null && content.type() != null) {
                    cardsByReviewId.put(review.id(), cards.get(toCoordinate(content)));
                }
            });
        }
        return cardsByReviewId;
    }

    private List<ContentCardSpec> distinctCardSpecs(Collection<ContentCardSpec> specs) {
        return specs.stream()
                .collect(Collectors.toMap(
                        ContentCardSpec::coordinate,
                        spec -> spec,
                        (first, ignored) -> first,
                        LinkedHashMap::new))
                .values().stream()
                .toList();
    }

    private ContentCardDTO cardFor(ContentRefDTO content, Map<ContentCoordinate, ContentCardDTO> cardsByCoordinate) {
        if (content == null || content.type() == null) {
            return null;
        }
        return cardsByCoordinate.get(toCoordinate(content));
    }

    private ContentCoordinate toCoordinate(ContentRefDTO content) {
        return new ContentCoordinate(content.type(), content.tmdbId(), content.seriesTmdbId(),
                content.seasonNumber(), content.episodeNumber());
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
        if (diaryEntryCreationDTO.customPosterUrl() != null) {
            userContentPosterService.upsert(userId, contentRef.id(), diaryEntryCreationDTO.customPosterUrl());
        }
        saveCompanions(entry, companionIds);

        removeFromWatchlistAndDropped(userId, contentRef);

        CompletionSignal completion = triggerCompletionCascade(userId, content, entry.getWatchedDate(), content.getType());

        List<UUID> resultIds = Stream.of(entry, completion.completedSeason(), completion.completedSeries())
                .filter(Objects::nonNull)
                .map(DiaryEntry::getId)
                .toList();
        Map<UUID, List<UserPreviewDTO>> watchedWithByEntryId = loadWatchedWith(resultIds);
        List<DiaryEntry> resultEntries = Stream.of(entry, completion.completedSeason(), completion.completedSeries())
                .filter(Objects::nonNull)
                .toList();
        Map<UUID, String> customPosterByContentId = loadPostersForOwner(userId, resultEntries);

        return new DiaryEntryCreationResultDTO(
                enrichDiaryEntryResponse(entry, false, watchedWithByEntryId.getOrDefault(entry.getId(), List.of()),
                        customPosterByContentId.get(entry.getContent().getId())),
                completion.completedSeason() != null
                        ? enrichDiaryEntryResponse(completion.completedSeason(), false,
                                watchedWithByEntryId.getOrDefault(completion.completedSeason().getId(), List.of()),
                                customPosterByContentId.get(completion.completedSeason().getContent().getId()))
                        : null,
                completion.completedSeries() != null
                        ? enrichDiaryEntryResponse(completion.completedSeries(), false,
                                watchedWithByEntryId.getOrDefault(completion.completedSeries().getId(), List.of()),
                                customPosterByContentId.get(completion.completedSeries().getContent().getId()))
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
        Map<UUID, String> customPosterByContentId = loadPostersForOwner(userId, created);

        return created.stream()
                .map(entry -> enrichDiaryEntryResponse(entry, false,
                        watchedWithByEntryId.getOrDefault(entry.getId(), List.of()),
                        customPosterByContentId.get(entry.getContent().getId())))
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
        if (diaryEntryUpdateDTO.watchedWith() != null) {
            List<UUID> companionIds = validateCompanions(userId, diaryEntryUpdateDTO.watchedWith());
            watchCompanionRepository.deleteByDiaryEntryId(entry.getId());
            saveCompanions(entry, companionIds);
        }

        entry.setAutoGenerated(false);
        entry.setIgnore(false);
        entry.setUpdatedAt(LocalDateTime.now());

        DiaryEntry saved = diaryEntryRepository.save(entry);
        if (diaryEntryUpdateDTO.customPosterUrl() != null) {
            userContentPosterService.upsert(userId, saved.getContent().getId(), diaryEntryUpdateDTO.customPosterUrl());
        }
        boolean likedByMe = likeService.getLikedDiaryEntryIds(userId, List.of(saved.getId())).contains(saved.getId());
        List<UserPreviewDTO> watchedWith = loadWatchedWith(List.of(saved.getId())).getOrDefault(saved.getId(), List.of());
        Map<UUID, String> customPosterByContentId = loadPostersForOwner(userId, List.of(saved));
        return enrichDiaryEntryResponse(saved, likedByMe, watchedWith,
                customPosterByContentId.get(saved.getContent().getId()));
    }

    private DiaryEntryResponseDTO enrichDiaryEntryResponse(
            DiaryEntry entry, boolean likedByMe, long commentsCount,
            List<UserPreviewDTO> watchedWith, String customPosterUrl, ContentCardDTO card) {
        return diaryEntryMapper.diaryEntryToResponseDto(entry, likedByMe, watchedWith)
                .withCommentsCount(commentsCount)
                .withCustomPosterUrl(customPosterUrl)
                .withCard(card);
    }

    private DiaryEntryResponseDTO enrichDiaryEntryResponse(
            DiaryEntry entry, boolean likedByMe, long commentsCount,
            List<UserPreviewDTO> watchedWith, String customPosterUrl) {
        return enrichDiaryEntryResponse(entry, likedByMe, commentsCount, watchedWith, customPosterUrl, null);
    }

    private DiaryEntryResponseDTO enrichDiaryEntryResponse(
            DiaryEntry entry, boolean likedByMe, List<UserPreviewDTO> watchedWith, String customPosterUrl) {
        return enrichDiaryEntryResponse(entry, likedByMe, 0L, watchedWith, customPosterUrl);
    }

    private Map<ContentCoordinate, ContentCardDTO> assembleDiaryCards(
            UUID viewerId, User target, Collection<DiaryEntry> entries) {
        List<ContentCardSpec> specs = distinctCardSpecs(entries.stream()
                .map(DiaryEntry::getContent)
                .filter(content -> content != null && content.getType() != null)
                .map(content -> new ContentCardSpec(
                        ContentCoordinate.from(content), null, null, null, content.getRuntimeMinutes()))
                .toList());
        if (specs.isEmpty()) {
            return Map.of();
        }
        Map<ContentCoordinate, ContentCardDTO> cards = contentCardAssembler.assemble(
                specs,
                new ContentCardContext(target.getPreferredLanguage(), target.getPreferredRegion(), target.getId(), viewerId),
                cardFields(true, viewerId));
        return cards == null ? Map.of() : cards;
    }

    private Set<ContentCardFieldSet> cardFields(boolean includeSocialMetadata, UUID viewerId) {
        EnumSet<ContentCardFieldSet> fields = EnumSet.of(ContentCardFieldSet.BASIC_METADATA, ContentCardFieldSet.STATS);
        if (includeSocialMetadata) {
            fields.add(ContentCardFieldSet.SOCIAL_METADATA);
        }
        if (viewerId != null) {
            fields.add(ContentCardFieldSet.VIEWER_STATE);
            fields.add(ContentCardFieldSet.WATCHLIST_PROGRESS);
        }
        return Set.copyOf(fields);
    }

    private ContentCardDTO cardFor(Content content, Map<ContentCoordinate, ContentCardDTO> cardsByCoordinate) {
        if (content == null || content.getType() == null) {
            return null;
        }
        return cardsByCoordinate.get(ContentCoordinate.from(content));
    }

    private String customPosterUrl(
            DiaryEntry entry,
            Map<ContentCoordinate, ContentCardDTO> cardsByCoordinate,
            Map<UUID, String> fallbackPostersByContentId) {
        ContentCardDTO card = cardFor(entry.getContent(), cardsByCoordinate);
        if (card != null && card.customPosterUrl() != null) {
            return card.customPosterUrl();
        }
        return entry.getContent() == null
                ? null
                : fallbackPostersByContentId.get(entry.getContent().getId());
    }

    private Map<UUID, String> loadPostersForOwner(UUID ownerId, Collection<DiaryEntry> entries) {
        List<UUID> contentIds = entries.stream()
                .map(DiaryEntry::getContent)
                .filter(Objects::nonNull)
                .map(Content::getId)
                .distinct()
                .toList();
        return contentIds.isEmpty()
                ? Map.of()
                : userContentPosterService.findByUserAndContentIds(ownerId, contentIds);
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
