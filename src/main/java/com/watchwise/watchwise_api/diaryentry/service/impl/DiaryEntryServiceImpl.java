package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
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
        LocalDateTime now = LocalDateTime.now();

        DiaryEntry entry = DiaryEntry.builder()
                .user(user)
                .content(content)
                .comment(diaryEntryCreationDTO.comment())
                .score(diaryEntryCreationDTO.score())
                .watchedDate(diaryEntryCreationDTO.watchedDate())
                .isRewatch(Boolean.TRUE.equals(diaryEntryCreationDTO.isRewatch()))
                .watchedInTheater(diaryEntryCreationDTO.watchedInTheater())
                .customPosterUrl(diaryEntryCreationDTO.customPosterUrl())
                .createdAt(now)
                .updatedAt(now)
                .build();

        return diaryEntryMapper.diaryEntryToResponseDto(diaryEntryRepository.save(entry));
    }

    @Override
    @Transactional
    public DiaryEntryResponseDTO updateDiaryEntry(UUID userId, UUID diaryEntryId, DiaryEntryCreationDTO diaryEntryCreationDTO) {
        DiaryEntry entry = findOwnedEntry(userId, diaryEntryId);

        ContentRefDTO contentRef = contentService.getOrCreateReference(diaryEntryCreationDTO.content());
        entry.setContent(contentRepository.getReferenceById(contentRef.id()));

        if (diaryEntryCreationDTO.comment() != null) {
            entry.setComment(diaryEntryCreationDTO.comment());
        }
        if (diaryEntryCreationDTO.score() != null) {
            entry.setScore(diaryEntryCreationDTO.score());
        }
        if (diaryEntryCreationDTO.watchedDate() != null) {
            entry.setWatchedDate(diaryEntryCreationDTO.watchedDate());
        }
        if (diaryEntryCreationDTO.isRewatch() != null) {
            entry.setIsRewatch(diaryEntryCreationDTO.isRewatch());
        }
        if (diaryEntryCreationDTO.watchedInTheater() != null) {
            entry.setWatchedInTheater(diaryEntryCreationDTO.watchedInTheater());
        }
        if (diaryEntryCreationDTO.customPosterUrl() != null) {
            entry.setCustomPosterUrl(diaryEntryCreationDTO.customPosterUrl());
        }

        entry.setUpdatedAt(LocalDateTime.now());

        return diaryEntryMapper.diaryEntryToResponseDto(diaryEntryRepository.save(entry));
    }

    @Override
    @Transactional
    public void deleteDiaryEntry(UUID userId, UUID diaryEntryId) {
        DiaryEntry entry = findOwnedEntry(userId, diaryEntryId);

        diaryEntryRepository.delete(entry);
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
}