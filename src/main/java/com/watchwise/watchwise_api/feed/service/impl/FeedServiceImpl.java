package com.watchwise.watchwise_api.feed.service.impl;

import com.watchwise.watchwise_api.common.dto.CursorPageResponseDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.feed.dto.FeedEventType;
import com.watchwise.watchwise_api.feed.dto.FeedItemDTO;
import com.watchwise.watchwise_api.feed.service.FeedService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FeedServiceImpl implements FeedService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;
    private static final String CURSOR_SEPARATOR = "|";

    private final FollowerRepository followerRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final DroppedEntryRepository droppedEntryRepository;
    private final Top5EntryRepository top5EntryRepository;
    private final LikeService likeService;
    private final ContentMapper contentMapper;
    private final UserMapper userMapper;

    @Override
    public CursorPageResponseDTO<FeedItemDTO> getFeed(UUID userId, String cursor, Integer size) {
        int effectiveSize = resolveSize(size);
        FeedCursor decodedCursor = decodeCursor(cursor);
        LocalDateTime cursorCreatedAt = decodedCursor == null ? null : decodedCursor.createdAt();
        UUID cursorId = decodedCursor == null ? null : decodedCursor.id();

        List<UUID> followedIds = followerRepository.findFollowedIdsByFollowerIdAndStatus(userId, FollowStatus.ACCEPTED);
        if (followedIds.isEmpty()) {
            return new CursorPageResponseDTO<>(List.of(), effectiveSize, null, false);
        }

        PageRequest fetchLimit = PageRequest.of(0, effectiveSize + 1);

        List<DiaryEntry> diaryRaw = diaryEntryRepository.findFeedCandidates(followedIds, cursorCreatedAt, cursorId, fetchLimit);
        List<DroppedEntry> droppedRaw = droppedEntryRepository.findFeedCandidates(followedIds, cursorCreatedAt, cursorId, fetchLimit);
        List<Top5Entry> top5Raw = top5EntryRepository.findFeedCandidates(followedIds, cursorCreatedAt, cursorId, fetchLimit);

        boolean diaryHasMore = diaryRaw.size() > effectiveSize;
        boolean droppedHasMore = droppedRaw.size() > effectiveSize;
        boolean top5HasMore = top5Raw.size() > effectiveSize;

        List<DiaryEntry> diaryEntries = trim(diaryRaw, effectiveSize);
        List<DroppedEntry> droppedEntries = trim(droppedRaw, effectiveSize);
        List<Top5Entry> top5Entries = trim(top5Raw, effectiveSize);

        Set<UUID> likedDiaryEntryIds = likeService.getLikedDiaryEntryIds(
                userId, diaryEntries.stream().map(DiaryEntry::getId).toList());

        List<FeedCandidate> candidates = new ArrayList<>();
        for (DiaryEntry entry : diaryEntries) {
            candidates.add(new FeedCandidate(entry.getCreatedAt(), entry.getId(),
                    toDiaryFeedItem(entry, likedDiaryEntryIds.contains(entry.getId()))));
        }
        for (DroppedEntry entry : droppedEntries) {
            candidates.add(new FeedCandidate(entry.getCreatedAt(), entry.getId(), toDroppedFeedItem(entry)));
        }
        for (Top5Entry entry : top5Entries) {
            candidates.add(new FeedCandidate(entry.getCreatedAt(), entry.getId(), toTop5FeedItem(entry)));
        }

        candidates.sort(Comparator.comparing(FeedCandidate::createdAt).reversed()
                .thenComparing(c -> c.id().toString(), Comparator.reverseOrder()));

        boolean hasMoreBeyondPage = candidates.size() > effectiveSize;
        List<FeedCandidate> page = hasMoreBeyondPage ? candidates.subList(0, effectiveSize) : candidates;
        boolean hasNext = hasMoreBeyondPage || diaryHasMore || droppedHasMore || top5HasMore;

        String nextCursor = hasNext && !page.isEmpty() ? encodeCursor(page.get(page.size() - 1)) : null;
        List<FeedItemDTO> content = page.stream().map(FeedCandidate::item).toList();

        return new CursorPageResponseDTO<>(content, effectiveSize, nextCursor, hasNext);
    }

    private int resolveSize(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        if (size <= 0) {
            throw new BadRequestException("size must be greater than 0");
        }
        return Math.min(size, MAX_SIZE);
    }

    private static <T> List<T> trim(List<T> list, int size) {
        return list.size() > size ? list.subList(0, size) : list;
    }

    private FeedItemDTO toDiaryFeedItem(DiaryEntry entry, boolean likedByMe) {
        return new FeedItemDTO(
                FeedEventType.DIARY_ENTRY,
                entry.getId(),
                userMapper.userToUserPreviewDto(entry.getUser()),
                contentMapper.contentToContentRefDto(entry.getContent()),
                null,
                entry.getScore(),
                entry.getComment(),
                entry.getLikesCount(),
                likedByMe,
                entry.getCreatedAt());
    }

    private FeedItemDTO toDroppedFeedItem(DroppedEntry entry) {
        return new FeedItemDTO(
                FeedEventType.DROPPED,
                entry.getId(),
                userMapper.userToUserPreviewDto(entry.getUser()),
                contentMapper.contentToContentRefDto(entry.getContent()),
                null,
                null,
                entry.getComment(),
                null,
                null,
                entry.getCreatedAt());
    }

    private FeedItemDTO toTop5FeedItem(Top5Entry entry) {
        return new FeedItemDTO(
                FeedEventType.TOP5_UPDATE,
                entry.getId(),
                userMapper.userToUserPreviewDto(entry.getUser()),
                null,
                entry.getType(),
                null,
                null,
                null,
                null,
                entry.getCreatedAt());
    }

    private FeedCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separatorIndex = decoded.lastIndexOf(CURSOR_SEPARATOR);
            LocalDateTime createdAt = LocalDateTime.parse(decoded.substring(0, separatorIndex));
            UUID id = UUID.fromString(decoded.substring(separatorIndex + 1));
            return new FeedCursor(createdAt, id);
        } catch (IllegalArgumentException | DateTimeParseException | IndexOutOfBoundsException e) {
            throw new BadRequestException("Invalid cursor");
        }
    }

    private String encodeCursor(FeedCandidate lastItem) {
        String raw = lastItem.createdAt() + CURSOR_SEPARATOR + lastItem.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record FeedCursor(LocalDateTime createdAt, UUID id) {
    }

    private record FeedCandidate(LocalDateTime createdAt, UUID id, FeedItemDTO item) {
    }
}
