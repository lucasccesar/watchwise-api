package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.transaction.UserContentMutationLock;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diarycompletion.repository.UserSeriesHistoryStateRepository;
import com.watchwise.watchwise_api.diarycompletion.service.impl.DiaryProjectionInvalidator;
import com.watchwise.watchwise_api.diaryentry.dto.WatchEventCommand;
import com.watchwise.watchwise_api.diaryentry.dto.WatchMutationResult;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.entity.WatchCompanion;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.diaryentry.service.DiaryWatchCommandService;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.service.WatchlistEntryService;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DiaryWatchCommandServiceImpl implements DiaryWatchCommandService {

    private static final int MAX_WATCHED_WITH = 20;
    private static final int MAX_COMMENT_LENGTH = 20_000;

    private final DiaryEntryRepository diaryEntryRepository;
    private final ContentRepository contentRepository;
    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final WatchCompanionRepository watchCompanionRepository;
    private final UserContentMutationLock userContentMutationLock;
    private final WatchlistEntryService watchlistEntryService;
    private final DroppedEntryRepository droppedEntryRepository;
    private final DiaryProjectionInvalidator diaryProjectionInvalidator;
    private final UserSeriesHistoryStateRepository historyStateRepository;

    @Override
    @Transactional
    public WatchMutationResult recordPrepared(UUID owner, WatchEventCommand command) {
        validateCommand(owner, command);
        userRepository.findById(owner).orElseThrow(() -> new NotFoundException("User not found"));

        MediaReference media = command.content();
        Content content = findPreparedContent(media);
        validateResolvedContent(media, content);
        List<UUID> companionIds = validateCompanions(owner, command.watchedWith());

        userContentMutationLock.lock(owner);
        int watchNumber = command.requestedWatchNumber() == null
                ? diaryEntryRepository.findMaxWatchNumber(owner, content.getId()) + 1
                : command.requestedWatchNumber();
        if (watchNumber <= 0) {
            throw new BadRequestException("requestedWatchNumber must be greater than or equal to 1");
        }

        Optional<DiaryEntry> existing = diaryEntryRepository.findFirstByUserIdAndContentIdAndWatchNumber(
                owner, content.getId(), watchNumber);
        if (existing.isPresent()) {
            return new WatchMutationResult(existing.get().getId(), false, currentHistoryRevision(owner, media));
        }

        LocalDateTime now = LocalDateTime.now();
        DiaryEntry entry = DiaryEntry.builder()
                .user(userRepository.getReferenceById(owner))
                .content(content)
                .comment(command.comment())
                .score(command.score())
                .watchedDate(command.watchedDate())
                .watchedAt(command.watchedAt())
                .watchNumber(watchNumber)
                .watchedInTheater(command.watchedInTheater())
                .ignore(false)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            entry = diaryEntryRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException conflict) {
            throw mapWatchNumberConflict(conflict);
        }
        saveCompanions(entry, companionIds);
        removeFromWatchlistAndDropped(owner, content, media);

        long revision = 0L;
        if (media.type() == ContentType.EPISODE) {
            diaryProjectionInvalidator.markSeriesChanged(owner, media.seriesTmdbId());
            revision = currentHistoryRevision(owner, media);
        }
        return new WatchMutationResult(entry.getId(), true, revision);
    }

    private void validateCommand(UUID owner, WatchEventCommand command) {
        if (owner == null || command == null || command.content() == null) {
            throw new BadRequestException("A diary watch command is required");
        }
        ContentType type = command.content().type();
        if (type != ContentType.MOVIE && type != ContentType.EPISODE) {
            throw new BadRequestException("Watch commands only support content of type MOVIE or EPISODE");
        }
        if (command.requestedWatchNumber() != null && command.requestedWatchNumber() < 1) {
            throw new BadRequestException("requestedWatchNumber must be greater than or equal to 1");
        }
        if (command.score() != null && (command.score() < 1 || command.score() > 10)) {
            throw new BadRequestException("score must be between 1 and 10");
        }
        if (command.comment() != null && command.comment().length() > MAX_COMMENT_LENGTH) {
            throw new BadRequestException("comment must not exceed 20000 characters");
        }
        if (command.watchedDate() != null && command.watchedDate().isAfter(LocalDate.now())) {
            throw new BadRequestException("watchedDate cannot be in the future");
        }
        if (command.watchedInTheater() != null && type != ContentType.MOVIE) {
            throw new BadRequestException("watchedInTheater can only be set for MOVIE content");
        }
        if (command.watchedWith() != null
                && (command.watchedWith().size() > MAX_WATCHED_WITH || command.watchedWith().stream().anyMatch(id -> id == null))) {
            throw new BadRequestException("watchedWith must contain at most 20 user IDs");
        }
    }

    private Content findPreparedContent(MediaReference media) {
        return switch (media.type()) {
            case MOVIE -> contentRepository.findByTmdbIdAndType(media.tmdbId(), ContentType.MOVIE)
                    .orElseThrow(() -> new NotFoundException("Content not found"));
            case EPISODE -> contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                            media.seriesTmdbId(), media.seasonNumber(), media.episodeNumber(), ContentType.EPISODE)
                    .orElseThrow(() -> new NotFoundException("Content not found"));
            default -> throw new BadRequestException("Watch commands only support content of type MOVIE or EPISODE");
        };
    }

    private void validateResolvedContent(MediaReference media, Content content) {
        if (content.getType() != media.type()) {
            throw new BadRequestException("Resolved content type does not match the watch command");
        }
        if (media.type() == ContentType.EPISODE
                && (!media.seriesTmdbId().equals(content.getSeriesTmdbId())
                || !media.seasonNumber().equals(content.getSeasonNumber())
                || !media.episodeNumber().equals(content.getEpisodeNumber()))) {
            throw new BadRequestException("Resolved episode coordinates do not match the watch command");
        }
        if (media.type() == ContentType.MOVIE && !media.tmdbId().equals(content.getTmdbId())) {
            throw new BadRequestException("Resolved movie does not match the watch command");
        }
    }

    private List<UUID> validateCompanions(UUID ownerId, List<UUID> companionIds) {
        if (companionIds == null || companionIds.isEmpty()) {
            return List.of();
        }
        List<UUID> distinct = companionIds.stream().distinct().toList();
        for (UUID companionId : distinct) {
            if (ownerId.equals(companionId)) {
                throw new BadRequestException("watchedWith cannot include yourself");
            }
            if (!followerRepository.existsByFollowerIdAndFollowedIdAndStatus(ownerId, companionId, FollowStatus.ACCEPTED)) {
                throw new BadRequestException("watchedWith can only include users you follow");
            }
        }
        return distinct;
    }

    private void saveCompanions(DiaryEntry entry, List<UUID> companionIds) {
        if (companionIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        watchCompanionRepository.saveAll(companionIds.stream()
                .map(companionId -> WatchCompanion.builder()
                        .diaryEntry(entry)
                        .user(userRepository.getReferenceById(companionId))
                        .createdAt(now)
                        .build())
                .toList());
    }

    private void removeFromWatchlistAndDropped(UUID ownerId, Content content, MediaReference media) {
        if (media.type() == ContentType.MOVIE) {
            removeContentFromWatchlistAndDropped(ownerId, ContentType.MOVIE, content.getId());
            return;
        }
        contentRepository.findByTmdbIdAndType(media.seriesTmdbId(), ContentType.SERIES)
                .ifPresent(series -> removeContentFromWatchlistAndDropped(ownerId, ContentType.SERIES, series.getId()));
    }

    private void removeContentFromWatchlistAndDropped(UUID ownerId, ContentType type, UUID contentId) {
        watchlistEntryService.removeEntryIfPresent(ownerId, type, contentId);
        droppedEntryRepository.findByUserIdAndTypeAndContentId(ownerId, type, contentId)
                .ifPresent(droppedEntryRepository::delete);
    }

    private long currentHistoryRevision(UUID ownerId, MediaReference media) {
        if (media.type() != ContentType.EPISODE) {
            return 0L;
        }
        return historyStateRepository.findHistoryRevision(ownerId, media.seriesTmdbId()).orElse(0L);
    }

    private ConflictException mapWatchNumberConflict(DataIntegrityViolationException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof ConstraintViolationException constraint
                && "uq_diary_entries_user_content_watch_number".equals(constraint.getConstraintName())) {
            return new ConflictException("This watch was already logged by a concurrent request");
        }
        return new ConflictException("Diary entry could not be saved due to a conflict");
    }
}
