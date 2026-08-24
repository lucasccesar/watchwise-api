package com.watchwise.watchwise_api.dropped.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.dropped.dto.DroppedEntryCreationDTO;
import com.watchwise.watchwise_api.dropped.dto.DroppedEntryResponseDTO;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.mapper.DroppedEntryMapper;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.dropped.service.DroppedEntryService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.service.WatchlistEntryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DroppedEntryServiceImpl implements DroppedEntryService {

    private final DroppedEntryRepository droppedEntryRepository;
    private final UserRepository userRepository;
    private final ContentRepository contentRepository;
    private final ContentService contentService;
    private final FollowerRepository followerRepository;
    private final DroppedEntryMapper droppedEntryMapper;
    private final NewTransactionExecutor newTransactionExecutor;
    private final WatchlistEntryService watchlistEntryService;

    static final int DEFAULT_PAGE = 0;
    static final int DEFAULT_PAGE_SIZE = 20;

    @Override
    public Page<DroppedEntryResponseDTO> getDropped(UUID viewerId, UUID userId, ContentType type, Integer pageNumber, Integer pageSize) {
        validateType(type);

        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        assertCanViewDropped(viewerId, userId, target);

        PageRequest pageRequest = buildPageRequest(pageNumber, pageSize);

        return droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(userId, type, pageRequest)
                .map(droppedEntryMapper::droppedEntryToResponseDto);
    }

    private void assertCanViewDropped(UUID viewerId, UUID targetUserId, User target) {
        if (Boolean.TRUE.equals(target.getIsProfilePublic()) || viewerId.equals(targetUserId)) {
            return;
        }

        boolean viewerFollowsTarget = followerRepository
                .existsByFollowerIdAndFollowedIdAndStatus(viewerId, targetUserId, FollowStatus.ACCEPTED);

        if (!viewerFollowsTarget) {
            throw new ForbiddenException("This user profile is private");
        }
    }

    @Override
    @Transactional
    public void markAsDropped(UUID userId, ContentType type, String tmdbId, DroppedEntryCreationDTO droppedEntryCreationDTO) {
        validateType(type);

        ContentRefDTO contentRef = contentService.getOrCreateReference(
                new ContentRefCreationDTO(tmdbId, type, null, null, null, null, null));

        watchlistEntryService.removeEntryIfPresent(userId, type, contentRef.id());

        Optional<DroppedEntry> existing = droppedEntryRepository.findByUserIdAndTypeAndContentId(userId, type, contentRef.id());
        if (existing.isPresent()) {
            applyCommentIfProvided(existing.get(), droppedEntryCreationDTO);
            return;
        }

        try {
            newTransactionExecutor.runInNewTransaction(() -> {
                User user = userRepository.getReferenceById(userId);
                Content content = contentRepository.getReferenceById(contentRef.id());
                LocalDateTime now = LocalDateTime.now();
                DroppedEntry entry = DroppedEntry.builder()
                        .user(user)
                        .content(content)
                        .type(type)
                        .comment(droppedEntryCreationDTO == null ? null : droppedEntryCreationDTO.comment())
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                return droppedEntryRepository.saveAndFlush(entry);
            });
        } catch (DataIntegrityViolationException e) {
            DroppedEntry recovered = droppedEntryRepository.findByUserIdAndTypeAndContentId(userId, type, contentRef.id())
                    .orElseThrow(() -> e);
            applyCommentIfProvided(recovered, droppedEntryCreationDTO);
        }
    }

    private void applyCommentIfProvided(DroppedEntry entry, DroppedEntryCreationDTO droppedEntryCreationDTO) {
        if (droppedEntryCreationDTO == null || droppedEntryCreationDTO.comment() == null) {
            return;
        }
        entry.setComment(droppedEntryCreationDTO.comment());
        entry.setUpdatedAt(LocalDateTime.now());
        droppedEntryRepository.save(entry);
    }

    @Override
    public void unmarkAsDropped(UUID userId, ContentType type, String tmdbId) {
        validateType(type);

        contentRepository.findByTmdbIdAndType(tmdbId, type)
                .flatMap(content -> droppedEntryRepository.findByUserIdAndTypeAndContentId(userId, type, content.getId()))
                .ifPresent(droppedEntryRepository::delete);
    }

    private void validateType(ContentType type) {
        if (type != ContentType.MOVIE && type != ContentType.SERIES) {
            throw new BadRequestException("type must be MOVIE or SERIES");
        }
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