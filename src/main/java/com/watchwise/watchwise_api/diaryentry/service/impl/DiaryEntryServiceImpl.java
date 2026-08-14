package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DiaryEntryServiceImpl implements DiaryEntryService {

    private final DiaryEntryRepository diaryEntryRepository;
    private final UserRepository userRepository;
    private final ContentRepository contentRepository;
    private final ContentService contentService;
    private final FollowerRepository followerRepository;
    private final DiaryEntryMapper diaryEntryMapper;

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

        DiaryEntry entry = persistDiaryEntry(
                user, content,
                diaryEntryCreationDTO.comment(), diaryEntryCreationDTO.score(), diaryEntryCreationDTO.watchedDate(),
                diaryEntryCreationDTO.isRewatch(), diaryEntryCreationDTO.watchedInTheater(),
                diaryEntryCreationDTO.customPosterUrl(), false);

        triggerCompletionCascade(userId, content, entry.getWatchedDate());

        return diaryEntryMapper.diaryEntryToResponseDto(entry);
    }

    private DiaryEntry persistDiaryEntry(User user, Content content, String comment, Integer score,
            LocalDate watchedDate, Boolean requestedIsRewatch, Boolean watchedInTheater,
            String customPosterUrl, boolean autoGenerated) {
        assertWatchedInTheaterAllowed(content.getType(), watchedInTheater);

        boolean alreadyLogged = diaryEntryRepository
                .findFirstByUserIdAndContentIdOrderByCreatedAtDesc(user.getId(), content.getId())
                .isPresent();

        LocalDateTime now = LocalDateTime.now();

        DiaryEntry entry = DiaryEntry.builder()
                .user(user)
                .content(content)
                .comment(comment)
                .score(score)
                .watchedDate(watchedDate)
                .isRewatch(alreadyLogged || Boolean.TRUE.equals(requestedIsRewatch))
                .watchedInTheater(watchedInTheater)
                .customPosterUrl(customPosterUrl)
                .autoGenerated(autoGenerated)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return diaryEntryRepository.save(entry);
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
        if (diaryEntryUpdateDTO.isRewatch() != null) {
            entry.setIsRewatch(diaryEntryUpdateDTO.isRewatch());
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
        }
    }

    private void retractSeasonIfIncomplete(UUID userId, String seriesTmdbId, Integer seasonNumber) {
        Optional<Content> seasonFinaleEpisode = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(seriesTmdbId, seasonNumber, ContentType.EPISODE);
        if (seasonFinaleEpisode.isEmpty()) {
            return;
        }

        Optional<Content> seasonContent = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(seriesTmdbId, seasonNumber, null, ContentType.SEASON);
        if (seasonContent.isEmpty()) {
            return;
        }

        Optional<DiaryEntry> seasonEntry = diaryEntryRepository
                .findFirstByUserIdAndContentIdOrderByCreatedAtDesc(userId, seasonContent.get().getId());
        if (seasonEntry.isEmpty() || !Boolean.TRUE.equals(seasonEntry.get().getAutoGenerated())) {
            return;
        }

        long watchedEpisodes = diaryEntryRepository.countDistinctWatchedEpisodesInSeason(userId, seriesTmdbId, seasonNumber);
        if (watchedEpisodes >= seasonFinaleEpisode.get().getEpisodeNumber()) {
            return;
        }

        diaryEntryRepository.delete(seasonEntry.get());
        diaryEntryRepository.flush();

        retractSeriesIfIncomplete(userId, seriesTmdbId);
    }

    private void retractSeriesIfIncomplete(UUID userId, String seriesTmdbId) {
        Optional<Content> seriesFinaleSeason = contentRepository
                .findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(seriesTmdbId, ContentType.SEASON);
        if (seriesFinaleSeason.isEmpty()) {
            return;
        }

        Optional<Content> seriesContent = contentRepository.findByTmdbIdAndType(seriesTmdbId, ContentType.SERIES);
        if (seriesContent.isEmpty()) {
            return;
        }

        Optional<DiaryEntry> seriesEntry = diaryEntryRepository
                .findFirstByUserIdAndContentIdOrderByCreatedAtDesc(userId, seriesContent.get().getId());
        if (seriesEntry.isEmpty() || !Boolean.TRUE.equals(seriesEntry.get().getAutoGenerated())) {
            return;
        }

        long watchedSeasons = diaryEntryRepository.countDistinctWatchedSeasonsInSeries(userId, seriesTmdbId);
        if (watchedSeasons >= seriesFinaleSeason.get().getSeasonNumber()) {
            return;
        }

        diaryEntryRepository.delete(seriesEntry.get());
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

    private void maybeCompleteSeason(UUID userId, String seriesTmdbId, Integer seasonNumber, LocalDate watchedDate) {
        Optional<Content> seasonFinaleEpisode = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(seriesTmdbId, seasonNumber, ContentType.EPISODE);
        if (seasonFinaleEpisode.isEmpty()) {
            return;
        }

        long watchedEpisodes = diaryEntryRepository.countDistinctWatchedEpisodesInSeason(userId, seriesTmdbId, seasonNumber);
        if (watchedEpisodes < seasonFinaleEpisode.get().getEpisodeNumber()) {
            return;
        }

        ContentRefDTO seasonRef = contentService.getOrCreateReference(new ContentRefCreationDTO(
                null, ContentType.SEASON, seriesTmdbId, seasonNumber, null, null, seasonFinaleEpisode.get().getIsSeriesFinale()));

        if (diaryEntryRepository.findFirstByUserIdAndContentIdOrderByCreatedAtDesc(userId, seasonRef.id()).isPresent()) {
            return;
        }

        User user = userRepository.getReferenceById(userId);
        Content seasonContent = contentRepository.getReferenceById(seasonRef.id());

        DiaryEntry seasonEntry = persistDiaryEntry(user, seasonContent, null, null, watchedDate, null, null, null, true);

        triggerCompletionCascade(userId, seasonContent, seasonEntry.getWatchedDate());
    }

    private void maybeCompleteSeries(UUID userId, String seriesTmdbId, LocalDate watchedDate) {
        Optional<Content> seriesFinaleSeason = contentRepository
                .findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(seriesTmdbId, ContentType.SEASON);
        if (seriesFinaleSeason.isEmpty()) {
            return;
        }

        long watchedSeasons = diaryEntryRepository.countDistinctWatchedSeasonsInSeries(userId, seriesTmdbId);
        if (watchedSeasons < seriesFinaleSeason.get().getSeasonNumber()) {
            return;
        }

        ContentRefDTO seriesRef = contentService.getOrCreateReference(new ContentRefCreationDTO(
                seriesTmdbId, ContentType.SERIES, null, null, null, null, null));

        if (diaryEntryRepository.findFirstByUserIdAndContentIdOrderByCreatedAtDesc(userId, seriesRef.id()).isPresent()) {
            return;
        }

        User user = userRepository.getReferenceById(userId);
        Content seriesContent = contentRepository.getReferenceById(seriesRef.id());

        persistDiaryEntry(user, seriesContent, null, null, watchedDate, null, null, null, true);
    }
}