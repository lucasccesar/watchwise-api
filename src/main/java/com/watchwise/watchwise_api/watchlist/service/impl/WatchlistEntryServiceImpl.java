package com.watchwise.watchwise_api.watchlist.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.contentreleasedatesnapshot.service.ContentReleaseDateSnapshotService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryCreationDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryReorderDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryResponseDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistPageResponseDTO;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import com.watchwise.watchwise_api.watchlist.mapper.WatchlistEntryMapper;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import com.watchwise.watchwise_api.watchlist.service.WatchlistEntryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WatchlistEntryServiceImpl implements WatchlistEntryService {

    private final WatchlistEntryRepository watchlistEntryRepository;
    private final UserRepository userRepository;
    private final ContentRepository contentRepository;
    private final ContentService contentService;
    private final FollowerRepository followerRepository;
    private final WatchlistEntryMapper watchlistEntryMapper;
    private final PageRequestFactory pageRequestFactory;
    private final AdvisoryLock advisoryLock;
    private final ContentReleaseDateSnapshotService releaseDateSnapshotService;

    static final int POSITION_PARK_OFFSET = 1_000_000_000;

    @Override
    public WatchlistPageResponseDTO getWatchlist(
            UUID viewerId, UUID userId, ContentType type, Integer pageNumber, Integer pageSize) {
        validateType(type);

        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewWatchlist(viewerId, userId, target);

        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);
        Page<WatchlistEntry> entries = type == null
                ? watchlistEntryRepository.findByUserIdOrderByPositionAsc(userId, pageRequest)
                : watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(userId, type, pageRequest);
        List<WatchlistEntry> entriesForDateResolution = entriesForDateResolution(userId, type, entries);
        var dates = releaseDateSnapshotService.resolve(target, entriesForDateResolution).releaseDates();
        Page<WatchlistEntryResponseDTO> mapped = entries.map(entry -> withReleaseDate(
                watchlistEntryMapper.watchlistEntryToResponseDto(entry), dates.get(entry.getId())));
        long upcomingCount = releaseDateSnapshotService.countUpcoming(
                userId, type, target.getPreferredRegion(), LocalDate.now());
        return WatchlistPageResponseDTO.of(mapped, upcomingCount);
    }

    private List<WatchlistEntry> entriesForDateResolution(
            UUID userId, ContentType type, Page<WatchlistEntry> page) {
        Map<UUID, WatchlistEntry> entries = new LinkedHashMap<>();
        page.getContent().forEach(entry -> entries.put(entry.getId(), entry));
        if (type == null || type == ContentType.SERIES) {
            watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(userId, ContentType.SERIES)
                    .forEach(entry -> entries.putIfAbsent(entry.getId(), entry));
        }
        return new ArrayList<>(entries.values());
    }

    private WatchlistEntryResponseDTO withReleaseDate(
            WatchlistEntryResponseDTO response, LocalDate releaseDate) {
        return new WatchlistEntryResponseDTO(
                response.id(), response.type(), response.content(), response.position(),
                response.createdAt(), response.updatedAt(), releaseDate);
    }

    private void assertCanViewWatchlist(UUID viewerId, UUID targetUserId, User target) {
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
    public WatchlistEntryResponseDTO insertEntry(
            UUID userId, ContentType type, WatchlistEntryCreationDTO watchlistEntryCreationDTO) {
        validateType(type);
        advisoryLock.lock(lockIdentity(userId));

        ContentRefCreationDTO contentRefCreation = new ContentRefCreationDTO(
                watchlistEntryCreationDTO.tmdbId(), type, null, null, null, null, null);
        ContentRefDTO contentRef = contentService.getOrCreateReference(contentRefCreation);

        long currentCount = watchlistEntryRepository.countByUserId(userId);
        User user = userRepository.getReferenceById(userId);
        Content content = contentRepository.getReferenceById(contentRef.id());
        releaseDateSnapshotService.writeThrough(user, content);
        LocalDateTime now = LocalDateTime.now();

        WatchlistEntry newEntry = WatchlistEntry.builder()
                .user(user)
                .content(content)
                .type(type)
                .position(Math.toIntExact(currentCount + 1))
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            WatchlistEntry saved = watchlistEntryRepository.save(newEntry);
            watchlistEntryRepository.flush();
            return watchlistEntryMapper.watchlistEntryToResponseDto(saved);
        } catch (DataIntegrityViolationException e) {
            throw mapUniqueConstraintViolation(e);
        }
    }

    @Override
    @Transactional
    public void removeEntry(UUID userId, ContentType type, UUID watchlistEntryId) {
        validateType(type);
        advisoryLock.lock(lockIdentity(userId));

        WatchlistEntry entry = watchlistEntryRepository.findById(watchlistEntryId)
                .orElseThrow(() -> new NotFoundException("Watchlist entry not found"));

        if (!entry.getUser().getId().equals(userId) || entry.getType() != type) {
            throw new NotFoundException("Watchlist entry not found");
        }

        deleteAndCloseGap(entry);
    }

    @Override
    @Transactional
    public void removeEntryIfPresent(UUID userId, ContentType type, UUID contentId) {
        validateType(type);
        advisoryLock.lock(lockIdentity(userId));
        watchlistEntryRepository.findByUserIdAndTypeAndContentId(userId, type, contentId)
                .ifPresent(this::deleteAndCloseGap);
    }

    private void deleteAndCloseGap(WatchlistEntry entry) {
        UUID userId = entry.getUser().getId();
        int removedPosition = entry.getPosition();
        watchlistEntryRepository.delete(entry);
        watchlistEntryRepository.flush();
        watchlistEntryRepository.parkPositionsInRange(
                userId, removedPosition + 1, Integer.MAX_VALUE, POSITION_PARK_OFFSET);
        watchlistEntryRepository.settleParkedPositions(userId, POSITION_PARK_OFFSET, -1);
    }

    @Override
    @Transactional
    public WatchlistEntryResponseDTO moveEntry(
            UUID userId, ContentType type, UUID watchlistEntryId, WatchlistEntryReorderDTO reorderDTO) {
        validateType(type);
        advisoryLock.lock(lockIdentity(userId));

        WatchlistEntry entry = watchlistEntryRepository.findById(watchlistEntryId)
                .orElseThrow(() -> new NotFoundException("Watchlist entry not found"));

        if (!entry.getUser().getId().equals(userId) || entry.getType() != type) {
            throw new NotFoundException("Watchlist entry not found");
        }

        long currentCount = watchlistEntryRepository.countByUserId(userId);
        int oldPosition = entry.getPosition();
        int newPosition = reorderDTO.position();

        if (newPosition > currentCount) {
            throw new BadRequestException(
                    "position cannot be greater than " + currentCount + ", the last position in the watchlist");
        }
        if (newPosition == oldPosition) {
            return watchlistEntryMapper.watchlistEntryToResponseDto(entry);
        }

        try {
            return performMove(entry, oldPosition, newPosition, currentCount);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Watchlist entry could not be reordered due to a concurrent update");
        }
    }

    private WatchlistEntryResponseDTO performMove(
            WatchlistEntry entry, int oldPosition, int newPosition, long currentCount) {
        UUID userId = entry.getUser().getId();
        entry.setPosition(Math.toIntExact(currentCount + 1));
        watchlistEntryRepository.save(entry);
        watchlistEntryRepository.flush();

        boolean movingForward = newPosition < oldPosition;
        int rangeStart = movingForward ? newPosition : oldPosition + 1;
        int rangeEnd = movingForward ? oldPosition - 1 : newPosition;
        int shiftDelta = movingForward ? 1 : -1;

        watchlistEntryRepository.parkPositionsInRange(
                userId, rangeStart, rangeEnd, POSITION_PARK_OFFSET);
        watchlistEntryRepository.settleParkedPositions(userId, POSITION_PARK_OFFSET, shiftDelta);

        entry.setPosition(newPosition);
        WatchlistEntry saved = watchlistEntryRepository.save(entry);
        watchlistEntryRepository.flush();
        return watchlistEntryMapper.watchlistEntryToResponseDto(saved);
    }

    private ConflictException mapUniqueConstraintViolation(DataIntegrityViolationException e) {
        String constraintName = extractConstraintName(e);
        if ("uq_watchlist_entries_user_id_type_content_id".equals(constraintName)) {
            return new ConflictException("This content is already in your watchlist");
        }
        if ("uq_watchlist_entries_user_id_position".equals(constraintName)) {
            return new ConflictException("This position was just taken by a concurrent insert");
        }
        return new ConflictException("Unable to insert this content into your watchlist");
    }

    private String extractConstraintName(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
            return cve.getConstraintName();
        }
        return null;
    }

    private void validateType(ContentType type) {
        if (type != null && type != ContentType.MOVIE && type != ContentType.SERIES) {
            throw new BadRequestException("type must be MOVIE or SERIES");
        }
    }

    private String lockIdentity(UUID userId) {
        return "watchlist|" + userId;
    }
}
