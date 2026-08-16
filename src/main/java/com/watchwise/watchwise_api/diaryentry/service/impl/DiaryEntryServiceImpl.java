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
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationDTO;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    public DiaryEntryResponseDTO createDiaryEntry(UUID userId, DiaryEntryCreationDTO diaryEntryCreationDTO) {
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

        triggerCompletionCascade(userId, content, entry.getWatchedDate());

        return diaryEntryMapper.diaryEntryToResponseDto(entry);
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
    public void deleteDiaryEntry(UUID userId, UUID diaryEntryId) {
        DiaryEntry entry = findOwnedEntry(userId, diaryEntryId);
        Content content = entry.getContent();

        diaryEntryRepository.delete(entry);
        diaryEntryRepository.flush();

        if (content.getType() == ContentType.EPISODE) {
            retractSeasonIfIncomplete(userId, content.getSeriesTmdbId(), content.getSeasonNumber());
        } else if (content.getType() == ContentType.SEASON) {
            retractSeriesIfIncomplete(userId, content.getSeriesTmdbId());
        } else if (content.getType() == ContentType.SERIES) {
            wipeSeriesHistory(userId, content.getTmdbId(), false);
        }
    }

    private void wipeSeriesHistory(UUID userId, String seriesTmdbId, boolean overrideProtectedEntries) {
        List<DiaryEntry> candidates = Stream.of(
                        diaryEntryRepository.findAllEpisodeEntriesInSeries(userId, seriesTmdbId),
                        diaryEntryRepository.findAllSeasonEntriesInSeries(userId, seriesTmdbId),
                        diaryEntryRepository.findAllSeriesEntries(userId, seriesTmdbId))
                .flatMap(List::stream)
                .toList();

        List<DiaryEntry> toDelete = candidates.stream()
                .filter(entry -> overrideProtectedEntries || Boolean.TRUE.equals(entry.getAutoGenerated()))
                .toList();

        if (toDelete.isEmpty()) {
            return;
        }

        diaryEntryRepository.deleteAll(toDelete);
    }

    private void retractSeasonIfIncomplete(UUID userId, String seriesTmdbId, Integer seasonNumber) {
        List<DiaryEntry> toDelete = computeSeasonRetractionCandidates(userId, seriesTmdbId, seasonNumber).stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getAutoGenerated()))
                .toList();

        if (toDelete.isEmpty()) {
            return;
        }

        diaryEntryRepository.deleteAll(toDelete);
        diaryEntryRepository.flush();

        retractSeriesIfIncomplete(userId, seriesTmdbId);
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

    private void retractSeriesIfIncomplete(UUID userId, String seriesTmdbId) {
        List<DiaryEntry> toDelete = computeSeriesRetractionCandidates(userId, seriesTmdbId).stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getAutoGenerated()))
                .toList();

        if (toDelete.isEmpty()) {
            return;
        }

        diaryEntryRepository.deleteAll(toDelete);
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

    private void triggerCompletionCascade(UUID userId, Content loggedContent, LocalDate watchedDate) {
        if (loggedContent.getType() == ContentType.EPISODE) {
            maybeCompleteSeason(userId, loggedContent.getSeriesTmdbId(), loggedContent.getSeasonNumber(), watchedDate);
        } else if (loggedContent.getType() == ContentType.SEASON) {
            maybeCompleteSeries(userId, loggedContent.getSeriesTmdbId(), watchedDate);
        }
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

    private void maybeCompleteSeason(UUID userId, String seriesTmdbId, Integer seasonNumber, LocalDate watchedDate) {
        Optional<Content> seasonFinaleEpisode = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(seriesTmdbId, seasonNumber, ContentType.EPISODE);
        if (seasonFinaleEpisode.isEmpty()) {
            return;
        }

        int minCount = minEpisodeWatchCount(userId, seriesTmdbId, seasonNumber, seasonFinaleEpisode.get().getEpisodeNumber());
        if (minCount == 0) {
            return;
        }

        ContentRefDTO seasonRef = contentService.getOrCreateReference(new ContentRefCreationDTO(
                null, ContentType.SEASON, seriesTmdbId, seasonNumber, null, null, seasonFinaleEpisode.get().getIsSeriesFinale()));

        int currentMax = diaryEntryRepository.findMaxWatchNumber(userId, seasonRef.id());

        while (currentMax < minCount) {
            User user = userRepository.getReferenceById(userId);
            Content seasonContent = contentRepository.getReferenceById(seasonRef.id());
            int nextWatchNumber = currentMax + 1;

            DiaryEntry seasonEntry = persistAutoGeneratedEntry(userId, user, seasonContent, watchedDate, nextWatchNumber);

            triggerCompletionCascade(userId, seasonContent, seasonEntry.getWatchedDate());
            currentMax = nextWatchNumber;
        }
    }

    private int minEpisodeWatchCount(UUID userId, String seriesTmdbId, Integer seasonNumber, int finaleEpisodeNumber) {
        Map<Integer, Long> countsByEpisode = diaryEntryRepository
                .countEntriesByEpisodeNumberInSeason(userId, seriesTmdbId, seasonNumber).stream()
                .collect(Collectors.toMap(DiaryEntryRepository.EpisodeWatchCount::getEpisodeNumber, DiaryEntryRepository.EpisodeWatchCount::getCount));

        int minCount = Integer.MAX_VALUE;
        for (int episode = 1; episode <= finaleEpisodeNumber; episode++) {
            minCount = Math.min(minCount, countsByEpisode.getOrDefault(episode, 0L).intValue());
        }
        return minCount;
    }

    private void maybeCompleteSeries(UUID userId, String seriesTmdbId, LocalDate watchedDate) {
        Optional<Content> seriesFinaleSeason = contentRepository
                .findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(seriesTmdbId, ContentType.SEASON);
        if (seriesFinaleSeason.isEmpty()) {
            return;
        }

        int minMax = minSeasonWatchMax(userId, seriesTmdbId, seriesFinaleSeason.get().getSeasonNumber());
        if (minMax == 0) {
            return;
        }

        ContentRefDTO seriesRef = contentService.getOrCreateReference(new ContentRefCreationDTO(
                seriesTmdbId, ContentType.SERIES, null, null, null, null, null));

        int currentMax = diaryEntryRepository.findMaxWatchNumber(userId, seriesRef.id());

        while (currentMax < minMax) {
            User user = userRepository.getReferenceById(userId);
            Content seriesContent = contentRepository.getReferenceById(seriesRef.id());
            int nextWatchNumber = currentMax + 1;

            persistAutoGeneratedEntry(userId, user, seriesContent, watchedDate, nextWatchNumber);

            currentMax = nextWatchNumber;
        }
    }

    private int minSeasonWatchMax(UUID userId, String seriesTmdbId, int finaleSeasonNumber) {
        Map<Integer, Integer> maxBySeason = diaryEntryRepository
                .maxWatchNumberBySeasonInSeries(userId, seriesTmdbId).stream()
                .collect(Collectors.toMap(DiaryEntryRepository.SeasonWatchMax::getSeasonNumber, DiaryEntryRepository.SeasonWatchMax::getMaxWatchNumber));

        int minMax = Integer.MAX_VALUE;
        for (int season = 1; season <= finaleSeasonNumber; season++) {
            minMax = Math.min(minMax, maxBySeason.getOrDefault(season, 0));
        }
        return minMax;
    }
}