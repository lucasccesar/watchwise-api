package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbGenre;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCountry;
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
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.service.WatchlistEntryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
        return diaryEntryRepository.findSeriesInProgressByUserId(userId, pageRequest)
                .map(row -> new SeriesInProgressResponseDTO(
                        row.getSeriesTmdbId(), row.getMaxSeasonNumber(), row.getMaxEpisodeNumber(), row.getLastWatchedDate()));
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
        ContentRefDTO contentRef = contentService.getOrCreateReference(diaryEntryCreationDTO.content());

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
        assertWatchedInTheaterAllowed(content.getType(), watchedInTheater);

        int maxWatchNumber = diaryEntryRepository.findMaxWatchNumber(user.getId(), content.getId());
        boolean honorRewatchFlag = Boolean.TRUE.equals(requestedIsRewatch)
                && !(maxWatchNumber == 0 && participatesInCompletionTracking(content.getType()));
        int watchNumber = Math.max(maxWatchNumber + 1, honorRewatchFlag ? 2 : 1);

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
        List<UUID> companionIds = validateCompanions(userId, dto.watchedWith());
        String language = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"))
                .getPreferredLanguage();

        List<DiaryEntry> created = new ArrayList<>();
        String seriesTmdbId;
        if (content.type() == ContentType.SEASON) {
            seriesTmdbId = content.seriesTmdbId();
            bulkLogSeason(userId, content.seriesTmdbId(), content.seasonNumber(), content.isSeriesFinale(),
                    dto.finaleEpisodeNumber(), dto.watchedDate(), created, ContentType.SEASON, companionIds, language,
                    dto.episodeRuntimeMinutes());
        } else {
            seriesTmdbId = content.tmdbId();
            bulkLogSeries(userId, content.tmdbId(), dto.finaleSeasonNumber(), dto.seasonFinaleEpisodeNumbers(),
                    dto.watchedDate(), created, companionIds, language);
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

    private void bulkLogSeason(UUID userId, String seriesTmdbId, Integer seasonNumber, Boolean isSeriesFinale,
            Integer explicitFinaleEpisodeNumber, LocalDate watchedDate, List<DiaryEntry> created, ContentType requestedType,
            List<UUID> companionIds, String language, Map<Integer, Integer> episodeRuntimeMinutes) {
        TmdbSeasonFullDetails seasonDetails = fetchSeasonDetails(seriesTmdbId, seasonNumber, language);
        int finaleEpisodeNumber = resolveSeasonFinaleEpisodeNumber(seriesTmdbId, seasonNumber, explicitFinaleEpisodeNumber, seasonDetails);
        if (finaleEpisodeNumber > MAX_BULK_EPISODES && finaleEpisodeNumber > realSeasonEpisodeCount(seasonDetails)) {
            throw new BadRequestException("Season has more than " + MAX_BULK_EPISODES
                    + " episodes, exceeding the bulk log limit, and the requested episode count could not be verified against TMDB");
        }
        assertWatchedDateNotBeforeRelease(watchedDate, episodeAirDate(seasonDetails, finaleEpisodeNumber));

        for (int episodeNumber = 1; episodeNumber <= finaleEpisodeNumber; episodeNumber++) {
            Integer runtimeMinutes = episodeRuntimeMinutes == null ? null : episodeRuntimeMinutes.get(episodeNumber);
            created.add(bulkLogEpisode(userId, seriesTmdbId, seasonNumber, episodeNumber,
                    episodeNumber == finaleEpisodeNumber, isSeriesFinale, watchedDate, created, requestedType, companionIds,
                    runtimeMinutes, false));
        }
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

    private LocalDate episodeAirDate(TmdbSeasonFullDetails season, int episodeNumber) {
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
        if (season.episodes() == null) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        return (int) season.episodes().stream()
                .map(TmdbEpisodeSummary::airDate)
                .map(this::parseTmdbDate)
                .filter(airDate -> airDate != null && !airDate.isAfter(today))
                .count();
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
            TmdbSeasonFullDetails seasonDetails) {
        Optional<Content> existingFinale = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(seriesTmdbId, seasonNumber, ContentType.EPISODE);
        if (existingFinale.isPresent()) {
            return existingFinale.get().getEpisodeNumber();
        }

        int airedEpisodeCount = airedEpisodeCount(seasonDetails);
        if (airedEpisodeCount >= 1) {
            return airedEpisodeCount;
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
            ContentType requestedType, List<UUID> companionIds, Integer runtimeMinutes, boolean trustedRuntimeMinutes) {
        Boolean seasonFinaleFlag = isSeasonFinale ? Boolean.TRUE : null;
        Boolean seriesFinaleFlag = isSeasonFinale && Boolean.TRUE.equals(isSeriesFinale) ? Boolean.TRUE : null;
        ContentRefDTO episodeRef = contentService.getOrCreateReference(new ContentRefCreationDTO(
                null, ContentType.EPISODE, seriesTmdbId, seasonNumber, episodeNumber,
                seasonFinaleFlag, seriesFinaleFlag, runtimeMinutes, null), trustedRuntimeMinutes);

        User user = userRepository.getReferenceById(userId);
        Content episodeContent = contentRepository.getReferenceById(episodeRef.id());

        DiaryEntry entry;
        try {
            entry = persistDiaryEntry(user, episodeContent, null, null, watchedDate, null, null, null, false,
                    isBelowRequestedLevel(ContentType.EPISODE, requestedType));
        } catch (DataIntegrityViolationException e) {
            throw mapWatchNumberConflict(e);
        }
        saveCompanions(entry, companionIds);

        CompletionSignal completion = triggerCompletionCascade(userId, episodeContent, watchedDate, requestedType);
        if (completion.completedSeason() != null) {
            created.add(completion.completedSeason());
        }
        if (completion.completedSeries() != null) {
            created.add(completion.completedSeries());
        }

        return entry;
    }

    private void bulkLogSeries(UUID userId, String seriesTmdbId, Integer explicitFinaleSeasonNumber,
            Map<Integer, Integer> seasonFinaleEpisodeNumbers, LocalDate watchedDate, List<DiaryEntry> created,
            List<UUID> companionIds, String language) {
        backfillSeriesMetadataIfNeeded(seriesTmdbId, language);

        int finaleSeasonNumber = resolveSeriesFinaleSeasonNumber(seriesTmdbId, explicitFinaleSeasonNumber, language);

        Map<Integer, TmdbSeasonFullDetails> seasonDetailsByNumber = new LinkedHashMap<>();
        int totalEpisodes = 0;
        for (int seasonNumber = 1; seasonNumber <= finaleSeasonNumber; seasonNumber++) {
            TmdbSeasonFullDetails seasonDetails = fetchSeasonDetails(seriesTmdbId, seasonNumber, language);
            seasonDetailsByNumber.put(seasonNumber, seasonDetails);
            totalEpisodes += resolveSeasonFinaleEpisodeNumber(seriesTmdbId, seasonNumber,
                    explicitFinaleEpisodeNumberFor(seasonFinaleEpisodeNumbers, seasonNumber), seasonDetails);
        }
        if (totalEpisodes > MAX_BULK_EPISODES) {
            TmdbTvFullDetails series = tmdbClient.getTvFullDetails(seriesTmdbId, language).toOptional().orElseThrow(this::tmdbUnavailable);
            Integer realEpisodeCount = series.numberOfEpisodes();
            if (realEpisodeCount == null || totalEpisodes > realEpisodeCount) {
                throw new BadRequestException("Series has more than " + MAX_BULK_EPISODES
                        + " episodes, exceeding the bulk log limit, and the requested episode count could not be verified against TMDB");
            }
        }

        for (int seasonNumber = 1; seasonNumber <= finaleSeasonNumber; seasonNumber++) {
            boolean isSeriesFinaleSeason = seasonNumber == finaleSeasonNumber;
            TmdbSeasonFullDetails seasonDetails = seasonDetailsByNumber.get(seasonNumber);
            int finaleEpisodeNumber = resolveSeasonFinaleEpisodeNumber(seriesTmdbId, seasonNumber,
                    explicitFinaleEpisodeNumberFor(seasonFinaleEpisodeNumbers, seasonNumber), seasonDetails);
            if (isSeriesFinaleSeason) {
                assertWatchedDateNotBeforeRelease(watchedDate, episodeAirDate(seasonDetails, finaleEpisodeNumber));
            }
            Map<Integer, Integer> episodeRuntimeMinutes = episodeRuntimeMinutesFromTmdb(seasonDetails);
            for (int episodeNumber = 1; episodeNumber <= finaleEpisodeNumber; episodeNumber++) {
                created.add(bulkLogEpisode(userId, seriesTmdbId, seasonNumber, episodeNumber,
                        episodeNumber == finaleEpisodeNumber, isSeriesFinaleSeason, watchedDate, created,
                        ContentType.SERIES, companionIds, episodeRuntimeMinutes.get(episodeNumber), true));
            }
        }
    }

    private void backfillSeriesMetadataIfNeeded(String seriesTmdbId, String language) {
        Optional<Content> existing = contentRepository.findByTmdbIdAndType(seriesTmdbId, ContentType.SERIES);
        if (existing.isPresent() && hasMetadata(existing.get())) {
            return;
        }

        Optional<TmdbTvFullDetails> seriesDetails = tmdbClient.getTvFullDetails(seriesTmdbId, language).toOptional();
        if (seriesDetails.isEmpty()) {
            return;
        }

        List<String> genres = genreNames(seriesDetails.get().genres());
        List<String> countries = countryCodes(seriesDetails.get().productionCountries());
        Integer releaseYear = releaseYearOf(seriesDetails.get().firstAirDate());
        if (genres.isEmpty() && countries.isEmpty() && releaseYear == null) {
            return;
        }

        try {
            contentService.getOrCreateReference(new ContentRefCreationDTO(
                    seriesTmdbId, ContentType.SERIES, null, null, null, null, null, null,
                    genres.isEmpty() ? null : genres, releaseYear, countries.isEmpty() ? null : countries));
        } catch (ConflictException e) {
            return;
        }
    }

    private boolean hasMetadata(Content content) {
        return content.getGenres() != null && !content.getGenres().isEmpty()
                && content.getReleaseYear() != null
                && content.getCountries() != null && !content.getCountries().isEmpty();
    }

    private List<String> genreNames(List<TmdbGenre> genres) {
        if (genres == null) {
            return List.of();
        }
        return genres.stream().map(TmdbGenre::name).toList();
    }

    private List<String> countryCodes(List<TmdbProductionCountry> countries) {
        if (countries == null) {
            return List.of();
        }
        return countries.stream().map(TmdbProductionCountry::isoCode).toList();
    }

    private Integer releaseYearOf(String firstAirDate) {
        LocalDate date = parseTmdbDate(firstAirDate);
        return date == null ? null : date.getYear();
    }

    private Integer explicitFinaleEpisodeNumberFor(Map<Integer, Integer> seasonFinaleEpisodeNumbers, int seasonNumber) {
        return seasonFinaleEpisodeNumbers == null ? null : seasonFinaleEpisodeNumbers.get(seasonNumber);
    }

    private int resolveSeriesFinaleSeasonNumber(String seriesTmdbId, Integer explicitFinaleSeasonNumber, String language) {
        Optional<Content> existingFinale = contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(seriesTmdbId, ContentType.SEASON);
        if (existingFinale.isPresent()) {
            return existingFinale.get().getSeasonNumber();
        }

        Optional<TmdbTvFullDetails> series = tmdbClient.getTvFullDetails(seriesTmdbId, language).toOptional();
        if (series.isPresent()) {
            int latestAiredSeasonNumber = latestAiredSeasonNumber(series.get().seasons());
            if (latestAiredSeasonNumber >= 1) {
                return latestAiredSeasonNumber;
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

    private int latestAiredSeasonNumber(List<TmdbSeasonSummary> seasons) {
        if (seasons == null) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        return seasons.stream()
                .filter(season -> season.seasonNumber() != null && season.seasonNumber() > 0)
                .filter(season -> {
                    LocalDate airDate = parseTmdbDate(season.airDate());
                    return airDate != null && !airDate.isAfter(today);
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

    private DiaryEntry persistAutoGeneratedEntry(UUID userId, User user, Content content, LocalDate watchedDate,
            int expectedWatchNumber, boolean ignore, List<UUID> unanimousCompanionIds) {
        try {
            return newTransactionExecutor.runInNewTransaction(() -> {
                DiaryEntry entry = persistDiaryEntry(user, content, null, null, watchedDate, null, null, null, true, ignore);
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

        int minCount = minEpisodeWatchCount(userId, seriesTmdbId, seasonNumber, seasonFinaleEpisode.get().getEpisodeNumber());
        if (minCount == 0) {
            return null;
        }

        ContentRefDTO seasonRef = contentService.getOrCreateReference(new ContentRefCreationDTO(
                null, ContentType.SEASON, seriesTmdbId, seasonNumber, null, null, seasonFinaleEpisode.get().getIsSeriesFinale()));

        int currentMax = diaryEntryRepository.findMaxWatchNumber(userId, seasonRef.id());
        DiaryEntry lastCreated = null;
        boolean ignore = isBelowRequestedLevel(ContentType.SEASON, requestedType);

        while (currentMax < minCount) {
            User user = userRepository.getReferenceById(userId);
            Content seasonContent = contentRepository.getReferenceById(seasonRef.id());
            int nextWatchNumber = currentMax + 1;

            List<UUID> childEntryIds = diaryEntryRepository
                    .findEpisodeEntriesInSeasonByWatchNumber(userId, seriesTmdbId, seasonNumber, nextWatchNumber).stream()
                    .map(DiaryEntry::getId).toList();
            List<UUID> unanimousCompanions = computeUnanimousCompanions(childEntryIds);

            lastCreated = persistAutoGeneratedEntry(userId, user, seasonContent, watchedDate, nextWatchNumber, ignore, unanimousCompanions);
            currentMax = nextWatchNumber;
        }

        return lastCreated;
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

        int minMax = minSeasonWatchMax(userId, seriesTmdbId, seriesFinaleSeason.get().getSeasonNumber());
        if (minMax == 0) {
            return null;
        }

        ContentRefDTO seriesRef = contentService.getOrCreateReference(new ContentRefCreationDTO(
                seriesTmdbId, ContentType.SERIES, null, null, null, null, null));

        int currentMax = diaryEntryRepository.findMaxWatchNumber(userId, seriesRef.id());
        DiaryEntry lastCreated = null;
        boolean ignore = isBelowRequestedLevel(ContentType.SERIES, requestedType);

        while (currentMax < minMax) {
            User user = userRepository.getReferenceById(userId);
            Content seriesContent = contentRepository.getReferenceById(seriesRef.id());
            int nextWatchNumber = currentMax + 1;

            List<UUID> childEntryIds = diaryEntryRepository
                    .findSeasonEntriesInSeriesByWatchNumber(userId, seriesTmdbId, nextWatchNumber).stream()
                    .map(DiaryEntry::getId).toList();
            List<UUID> unanimousCompanions = computeUnanimousCompanions(childEntryIds);

            lastCreated = persistAutoGeneratedEntry(userId, user, seriesContent, watchedDate, nextWatchNumber, ignore, unanimousCompanions);
            currentMax = nextWatchNumber;
        }

        return lastCreated;
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
