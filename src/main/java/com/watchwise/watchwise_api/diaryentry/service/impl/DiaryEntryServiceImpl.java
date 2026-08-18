package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.diaryentry.dto.DeletionImpactDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DeletionImpactItemDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryBulkCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationResultDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryUpdateDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.mapper.DiaryEntryMapper;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.service.DiaryEntryService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final NewTransactionExecutor newTransactionExecutor;

    static final int DEFAULT_PAGE = 0;
    static final int DEFAULT_PAGE_SIZE = 20;

    @Override
    public Page<DiaryEntryResponseDTO> getDiaryEntries(UUID viewerId, UUID userId, Integer year, Integer pageNumber, Integer pageSize) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        assertCanViewDiary(viewerId, userId, target);

        PageRequest pageRequest = buildPageRequest(pageNumber, pageSize);

        Page<DiaryEntry> entries = year != null
                ? diaryEntryRepository.findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc(
                        userId, startOfYear(year), endOfYear(year), pageRequest)
                : diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(userId, pageRequest);

        return entries.map(diaryEntryMapper::diaryEntryToResponseDto);
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
        ContentRefDTO contentRef = contentService.getOrCreateReference(diaryEntryCreationDTO.content());

        User user = userRepository.getReferenceById(userId);
        Content content = contentRepository.getReferenceById(contentRef.id());

        DiaryEntry entry;
        try {
            entry = persistDiaryEntry(
                    user, content,
                    diaryEntryCreationDTO.comment(), diaryEntryCreationDTO.score(), diaryEntryCreationDTO.watchedDate(),
                    diaryEntryCreationDTO.isRewatch(), diaryEntryCreationDTO.watchedInTheater(),
                    diaryEntryCreationDTO.customPosterUrl(), false);
        } catch (DataIntegrityViolationException e) {
            throw mapWatchNumberConflict(e);
        }

        CompletionSignal completion = triggerCompletionCascade(userId, content, entry.getWatchedDate());

        return new DiaryEntryCreationResultDTO(
                diaryEntryMapper.diaryEntryToResponseDto(entry),
                completion.completedSeason() != null ? diaryEntryMapper.diaryEntryToResponseDto(completion.completedSeason()) : null,
                completion.completedSeries() != null ? diaryEntryMapper.diaryEntryToResponseDto(completion.completedSeries()) : null);
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
            String customPosterUrl, boolean autoGenerated) {
        assertWatchedInTheaterAllowed(content.getType(), watchedInTheater);

        int maxWatchNumber = diaryEntryRepository.findMaxWatchNumber(user.getId(), content.getId());
        int watchNumber = Math.max(maxWatchNumber + 1, Boolean.TRUE.equals(requestedIsRewatch) ? 2 : 1);

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

        List<DiaryEntry> created = new ArrayList<>();
        if (content.type() == ContentType.SEASON) {
            bulkLogSeason(userId, content.seriesTmdbId(), content.seasonNumber(), content.isSeriesFinale(),
                    dto.finaleEpisodeNumber(), dto.watchedDate(), created);
        } else {
            bulkLogSeries(userId, content.tmdbId(), dto.finaleSeasonNumber(), dto.seasonFinaleEpisodeNumbers(), dto.watchedDate(), created);
        }

        return created.stream().map(diaryEntryMapper::diaryEntryToResponseDto).toList();
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
            entry.setWatchedDate(diaryEntryUpdateDTO.watchedDate());
        }
        if (diaryEntryUpdateDTO.watchedInTheater() != null) {
            assertWatchedInTheaterAllowed(entry.getContent().getType(), diaryEntryUpdateDTO.watchedInTheater());
            entry.setWatchedInTheater(diaryEntryUpdateDTO.watchedInTheater());
        }
        if (diaryEntryUpdateDTO.customPosterUrl() != null) {
            entry.setCustomPosterUrl(diaryEntryUpdateDTO.customPosterUrl());
        }

        entry.setAutoGenerated(false);
        entry.setUpdatedAt(LocalDateTime.now());

        return diaryEntryMapper.diaryEntryToResponseDto(diaryEntryRepository.save(entry));
    }

    private void assertWatchedInTheaterAllowed(ContentType contentType, Boolean watchedInTheater) {
        if (watchedInTheater != null && contentType != ContentType.MOVIE) {
            throw new BadRequestException("watchedInTheater can only be set for content of type MOVIE");
        }
    }

    @Override
    @Transactional
    public void deleteDiaryEntry(UUID userId, UUID diaryEntryId, boolean overrideProtectedEntries) {
        deleteDiaryEntry(userId, diaryEntryId, overrideProtectedEntries, new ArrayList<>());
    }

    private void deleteDiaryEntry(UUID userId, UUID diaryEntryId, boolean overrideProtectedEntries, List<DiaryEntry> cascadeDeleted) {
        DiaryEntry entry = findOwnedEntry(userId, diaryEntryId);
        Content content = entry.getContent();

        diaryEntryRepository.delete(entry);
        diaryEntryRepository.flush();

        if (content.getType() == ContentType.EPISODE) {
            retractSeasonIfIncomplete(userId, content.getSeriesTmdbId(), content.getSeasonNumber(), overrideProtectedEntries, cascadeDeleted);
        } else if (content.getType() == ContentType.SEASON) {
            retractSeriesIfIncomplete(userId, content.getSeriesTmdbId(), overrideProtectedEntries, cascadeDeleted);
        } else if (content.getType() == ContentType.SERIES) {
            wipeSeriesHistory(userId, content.getTmdbId(), overrideProtectedEntries, cascadeDeleted);
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

    private void wipeSeriesHistory(UUID userId, String seriesTmdbId, boolean overrideProtectedEntries, List<DiaryEntry> cascadeDeleted) {
        deleteRespectingProtection(computeSeriesWipeCandidates(userId, seriesTmdbId), overrideProtectedEntries, cascadeDeleted);
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

    private List<DiaryEntry> computeSeriesWipeCandidates(UUID userId, String seriesTmdbId) {
        return Stream.of(
                        diaryEntryRepository.findAllEpisodeEntriesInSeries(userId, seriesTmdbId),
                        diaryEntryRepository.findAllSeasonEntriesInSeries(userId, seriesTmdbId),
                        diaryEntryRepository.findAllSeriesEntries(userId, seriesTmdbId))
                .flatMap(List::stream)
                .toList();
    }

    static final int MAX_BULK_EPISODES = 100;

    private void bulkLogSeason(UUID userId, String seriesTmdbId, Integer seasonNumber, Boolean isSeriesFinale,
            Integer explicitFinaleEpisodeNumber, LocalDate watchedDate, List<DiaryEntry> created) {
        int finaleEpisodeNumber = resolveSeasonFinaleEpisodeNumber(seriesTmdbId, seasonNumber, explicitFinaleEpisodeNumber);
        if (finaleEpisodeNumber > MAX_BULK_EPISODES) {
            throw new BadRequestException("Season has more than " + MAX_BULK_EPISODES + " episodes, exceeding the bulk log limit");
        }

        for (int episodeNumber = 1; episodeNumber <= finaleEpisodeNumber; episodeNumber++) {
            created.add(bulkLogEpisode(userId, seriesTmdbId, seasonNumber, episodeNumber,
                    episodeNumber == finaleEpisodeNumber, isSeriesFinale, watchedDate, created));
        }
    }

    private int resolveSeasonFinaleEpisodeNumber(String seriesTmdbId, Integer seasonNumber, Integer explicitFinaleEpisodeNumber) {
        Optional<Content> existingFinale = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(seriesTmdbId, seasonNumber, ContentType.EPISODE);
        if (existingFinale.isPresent()) {
            return existingFinale.get().getEpisodeNumber();
        }
        if (explicitFinaleEpisodeNumber == null) {
            throw new BadRequestException("finaleEpisodeNumber is required when season " + seasonNumber + " has no known finale episode yet");
        }
        if (explicitFinaleEpisodeNumber < 1) {
            throw new BadRequestException("finaleEpisodeNumber for season " + seasonNumber + " must be greater than or equal to 1");
        }
        return explicitFinaleEpisodeNumber;
    }

    private DiaryEntry bulkLogEpisode(UUID userId, String seriesTmdbId, Integer seasonNumber, int episodeNumber,
            boolean isSeasonFinale, Boolean isSeriesFinale, LocalDate watchedDate, List<DiaryEntry> created) {
        ContentRefDTO episodeRef = contentService.getOrCreateReference(new ContentRefCreationDTO(
                null, ContentType.EPISODE, seriesTmdbId, seasonNumber, episodeNumber,
                isSeasonFinale, isSeasonFinale ? isSeriesFinale : null));

        User user = userRepository.getReferenceById(userId);
        Content episodeContent = contentRepository.getReferenceById(episodeRef.id());

        DiaryEntry entry;
        try {
            entry = persistDiaryEntry(user, episodeContent, null, null, watchedDate, null, null, null, false);
        } catch (DataIntegrityViolationException e) {
            throw mapWatchNumberConflict(e);
        }

        CompletionSignal completion = triggerCompletionCascade(userId, episodeContent, watchedDate);
        if (completion.completedSeason() != null) {
            created.add(completion.completedSeason());
        }
        if (completion.completedSeries() != null) {
            created.add(completion.completedSeries());
        }

        return entry;
    }

    private void bulkLogSeries(UUID userId, String seriesTmdbId, Integer explicitFinaleSeasonNumber,
            Map<Integer, Integer> seasonFinaleEpisodeNumbers, LocalDate watchedDate, List<DiaryEntry> created) {
        int finaleSeasonNumber = resolveSeriesFinaleSeasonNumber(seriesTmdbId, explicitFinaleSeasonNumber);

        int totalEpisodes = 0;
        for (int seasonNumber = 1; seasonNumber <= finaleSeasonNumber; seasonNumber++) {
            totalEpisodes += resolveSeasonFinaleEpisodeNumber(seriesTmdbId, seasonNumber,
                    explicitFinaleEpisodeNumberFor(seasonFinaleEpisodeNumbers, seasonNumber));
        }
        if (totalEpisodes > MAX_BULK_EPISODES) {
            throw new BadRequestException("Series has more than " + MAX_BULK_EPISODES + " episodes, exceeding the bulk log limit");
        }

        for (int seasonNumber = 1; seasonNumber <= finaleSeasonNumber; seasonNumber++) {
            boolean isSeriesFinaleSeason = seasonNumber == finaleSeasonNumber;
            int finaleEpisodeNumber = resolveSeasonFinaleEpisodeNumber(seriesTmdbId, seasonNumber,
                    explicitFinaleEpisodeNumberFor(seasonFinaleEpisodeNumbers, seasonNumber));
            for (int episodeNumber = 1; episodeNumber <= finaleEpisodeNumber; episodeNumber++) {
                created.add(bulkLogEpisode(userId, seriesTmdbId, seasonNumber, episodeNumber,
                        episodeNumber == finaleEpisodeNumber, isSeriesFinaleSeason, watchedDate, created));
            }
        }
    }

    private Integer explicitFinaleEpisodeNumberFor(Map<Integer, Integer> seasonFinaleEpisodeNumbers, int seasonNumber) {
        return seasonFinaleEpisodeNumbers == null ? null : seasonFinaleEpisodeNumbers.get(seasonNumber);
    }

    private int resolveSeriesFinaleSeasonNumber(String seriesTmdbId, Integer explicitFinaleSeasonNumber) {
        Optional<Content> existingFinale = contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(seriesTmdbId, ContentType.SEASON);
        if (existingFinale.isPresent()) {
            return existingFinale.get().getSeasonNumber();
        }
        if (explicitFinaleSeasonNumber == null) {
            throw new BadRequestException("finaleSeasonNumber is required when the series has no known finale season yet");
        }
        return explicitFinaleSeasonNumber;
    }

    private DiaryEntry findOwnedEntry(UUID userId, UUID diaryEntryId) {
        DiaryEntry entry = diaryEntryRepository.findById(diaryEntryId)
                .orElseThrow(() -> new NotFoundException("Diary entry not found"));

        if (!entry.getUser().getId().equals(userId)) {
            throw new NotFoundException("Diary entry not found");
        }

        return entry;
    }

    public PageRequest buildPageRequest(Integer pageNumber, Integer pageSize) {
        int queryPageNumber;
        int queryPageSize;

        if (pageNumber != null && pageNumber > 0) {
            queryPageNumber = pageNumber - 1;
        } else if (pageNumber == null || pageNumber == 0) {
            queryPageNumber = DEFAULT_PAGE;
        } else {
            throw new BadRequestException("Page number must be greater than or equal to 0");
        }

        if (pageSize == null || pageSize > 1000) {
            queryPageSize = DEFAULT_PAGE_SIZE;
        } else {
            if (pageSize <= 0) {
                throw new BadRequestException("Page size must be greater than 0");
            } else {
                queryPageSize = pageSize;
            }
        }

        return PageRequest.of(queryPageNumber, queryPageSize);
    }

    private record CompletionSignal(DiaryEntry completedSeason, DiaryEntry completedSeries) {
        static final CompletionSignal NONE = new CompletionSignal(null, null);
    }

    private CompletionSignal triggerCompletionCascade(UUID userId, Content loggedContent, LocalDate watchedDate) {
        if (loggedContent.getType() == ContentType.EPISODE) {
            DiaryEntry completedSeason = maybeCompleteSeason(userId, loggedContent.getSeriesTmdbId(), loggedContent.getSeasonNumber(), watchedDate);
            if (completedSeason == null) {
                return CompletionSignal.NONE;
            }
            CompletionSignal seriesSignal = triggerCompletionCascade(userId, completedSeason.getContent(), completedSeason.getWatchedDate());
            return new CompletionSignal(completedSeason, seriesSignal.completedSeries());
        }
        if (loggedContent.getType() == ContentType.SEASON) {
            return new CompletionSignal(null, maybeCompleteSeries(userId, loggedContent.getSeriesTmdbId(), watchedDate));
        }
        return CompletionSignal.NONE;
    }

    private DiaryEntry persistAutoGeneratedEntry(UUID userId, User user, Content content, LocalDate watchedDate, int expectedWatchNumber) {
        try {
            return newTransactionExecutor.runInNewTransaction(
                    () -> persistDiaryEntry(user, content, null, null, watchedDate, null, null, null, true));
        } catch (DataIntegrityViolationException e) {
            return diaryEntryRepository
                    .findFirstByUserIdAndContentIdAndWatchNumber(userId, content.getId(), expectedWatchNumber)
                    .orElseThrow(() -> e);
        }
    }

    private DiaryEntry maybeCompleteSeason(UUID userId, String seriesTmdbId, Integer seasonNumber, LocalDate watchedDate) {
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

        while (currentMax < minCount) {
            User user = userRepository.getReferenceById(userId);
            Content seasonContent = contentRepository.getReferenceById(seasonRef.id());
            int nextWatchNumber = currentMax + 1;

            lastCreated = persistAutoGeneratedEntry(userId, user, seasonContent, watchedDate, nextWatchNumber);
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

    private DiaryEntry maybeCompleteSeries(UUID userId, String seriesTmdbId, LocalDate watchedDate) {
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

        while (currentMax < minMax) {
            User user = userRepository.getReferenceById(userId);
            Content seriesContent = contentRepository.getReferenceById(seriesRef.id());
            int nextWatchNumber = currentMax + 1;

            lastCreated = persistAutoGeneratedEntry(userId, user, seriesContent, watchedDate, nextWatchNumber);
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
