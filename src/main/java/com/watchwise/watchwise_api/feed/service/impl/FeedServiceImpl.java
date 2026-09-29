package com.watchwise.watchwise_api.feed.service.impl;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.service.CommentPreviewData;
import com.watchwise.watchwise_api.comment.service.impl.CommentPreviewAssembler;
import com.watchwise.watchwise_api.common.dto.CursorPageResponseDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameResultPreviewDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameResultStatus;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import com.watchwise.watchwise_api.dailygame.repository.UserDailyGameResultRepository;
import com.watchwise.watchwise_api.dailygame.service.impl.DailyChallengeResponseAssembler;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.entity.WatchCompanion;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.feed.dto.FeedEventType;
import com.watchwise.watchwise_api.feed.dto.FeedItemDTO;
import com.watchwise.watchwise_api.feed.service.FeedService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.dto.PickPreviewDTO;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.service.impl.PickPreviewAssembler;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.pickstemplate.service.impl.PicksTemplatePreviewAssembler;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;
import com.watchwise.watchwise_api.top5entry.mapper.Top5EntryMapper;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final UserDailyGameResultRepository dailyGameResultRepository;
    private final PickRepository pickRepository;
    private final PicksTemplateRepository picksTemplateRepository;
    private final WatchCompanionRepository watchCompanionRepository;
    private final LikeService likeService;
    private final ContentMapper contentMapper;
    private final Top5EntryMapper top5EntryMapper;
    private final UserContentPosterService userContentPosterService;
    private final UserMapper userMapper;
    private final DailyChallengeResponseAssembler dailyChallengeResponseAssembler;
    private final PickPreviewAssembler pickPreviewAssembler;
    private final PicksTemplatePreviewAssembler picksTemplatePreviewAssembler;
    private final CommentPreviewAssembler commentPreviewAssembler;

    @Override
    @Transactional(readOnly = true)
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
        List<Pick> pickRaw = pickRepository.findFeedCandidates(followedIds, userId, cursorCreatedAt, cursorId, fetchLimit);
        List<PicksTemplate> picksTemplateRaw = picksTemplateRepository.findFeedCandidates(
                followedIds, cursorCreatedAt, cursorId, fetchLimit);
        List<UserDailyGameResult> dailyGameRaw = dailyGameResultRepository.findFeedCandidates(
                followedIds, cursorCreatedAt, cursorId, fetchLimit);

        boolean diaryHasMore = diaryRaw.size() > effectiveSize;
        boolean droppedHasMore = droppedRaw.size() > effectiveSize;
        boolean top5HasMore = top5Raw.size() > effectiveSize;
        boolean pickHasMore = pickRaw.size() > effectiveSize;
        boolean picksTemplateHasMore = picksTemplateRaw.size() > effectiveSize;
        boolean dailyGameHasMore = dailyGameRaw.size() > effectiveSize;

        List<DiaryEntry> diaryEntries = trim(diaryRaw, effectiveSize);
        List<DroppedEntry> droppedEntries = trim(droppedRaw, effectiveSize);
        List<Top5Entry> top5Entries = trim(top5Raw, effectiveSize);
        List<Pick> pickEntries = trim(pickRaw, effectiveSize);
        List<PicksTemplate> picksTemplateEntries = trim(picksTemplateRaw, effectiveSize);
        Map<Top5Key, List<Top5EntryResponseDTO>> currentTop5ByKey = loadCurrentTop5Previews(top5Entries);
        List<UserDailyGameResult> dailyGameEntries = trim(dailyGameRaw, effectiveSize);

        Set<UUID> likedDiaryEntryIds = likeService.getLikedDiaryEntryIds(
                userId, diaryEntries.stream().map(DiaryEntry::getId).toList());
        Set<UUID> likedDroppedEntryIds = likeService.getLikedDroppedEntryIds(
                userId, droppedEntries.stream().map(DroppedEntry::getId).toList());
        Map<UUID, List<UserPreviewDTO>> watchedWithByEntryId = watchCompanionRepository
                .findByDiaryEntryIdIn(diaryEntries.stream().map(DiaryEntry::getId).toList()).stream()
                .collect(Collectors.groupingBy(wc -> wc.getDiaryEntry().getId(),
                        Collectors.mapping(wc -> userMapper.userToUserPreviewDto(wc.getUser()), Collectors.toList())));
        Map<UUID, CommentPreviewData> diaryCommentPreviews = commentPreviewAssembler.assembleDiaryEntryPreviews(
                diaryEntries.stream().map(DiaryEntry::getId).toList(), userId);
        Map<UUID, CommentPreviewData> droppedCommentPreviews = commentPreviewAssembler.assembleDroppedEntryPreviews(
                droppedEntries.stream().map(DroppedEntry::getId).toList(), userId);
        Map<UUID, PickPreviewDTO> pickPreviews = pickPreviewAssembler.assembleForFeed(pickEntries, userId);
        Map<UUID, PicksTemplate> templatesById = new LinkedHashMap<>();
        pickEntries.forEach(pick -> templatesById.put(pick.getPicksTemplate().getId(), pick.getPicksTemplate()));
        picksTemplateEntries.forEach(template -> templatesById.put(template.getId(), template));
        Map<UUID, PicksTemplatePreviewDTO> picksTemplatePreviews = picksTemplatePreviewAssembler
                .assembleForFeed(templatesById.values(), userId);

        List<FeedCandidate> candidates = new ArrayList<>();
        for (DiaryEntry entry : diaryEntries) {
            candidates.add(new FeedCandidate(entry.getCreatedAt(), entry.getId(),
                    toDiaryFeedItem(entry, likedDiaryEntryIds.contains(entry.getId()),
                            watchedWithByEntryId.getOrDefault(entry.getId(), List.of()),
                            diaryCommentPreviews.get(entry.getId()))));
        }
        for (DroppedEntry entry : droppedEntries) {
            candidates.add(new FeedCandidate(entry.getCreatedAt(), entry.getId(),
                    toDroppedFeedItem(entry, likedDroppedEntryIds.contains(entry.getId()),
                            droppedCommentPreviews.get(entry.getId()))));
        }
        for (Top5Entry entry : top5Entries) {
            candidates.add(new FeedCandidate(entry.getCreatedAt(), entry.getId(),
                    toTop5FeedItem(entry, currentTop5ByKey.getOrDefault(top5Key(entry), List.of()))));
        }
        for (Pick entry : pickEntries) {
            candidates.add(new FeedCandidate(entry.getCreatedAt(), entry.getId(),
                    toPickFeedItem(entry, pickPreviews.get(entry.getId()),
                            picksTemplatePreviews.get(entry.getPicksTemplate().getId()))));
        }
        for (PicksTemplate entry : picksTemplateEntries) {
            candidates.add(new FeedCandidate(entry.getCreatedAt(), entry.getId(),
                    toPicksTemplateFeedItem(entry, picksTemplatePreviews.get(entry.getId()))));
        }
        for (UserDailyGameResult entry : dailyGameEntries) {
            candidates.add(new FeedCandidate(entry.getSharedAt(), entry.getId(), toDailyGameFeedItem(entry)));
        }

        candidates.sort(Comparator.comparing(FeedCandidate::createdAt).reversed()
                .thenComparing(c -> c.id().toString(), Comparator.reverseOrder()));

        boolean hasMoreBeyondPage = candidates.size() > effectiveSize;
        List<FeedCandidate> page = hasMoreBeyondPage ? candidates.subList(0, effectiveSize) : candidates;
        boolean hasNext = hasMoreBeyondPage || diaryHasMore || droppedHasMore || top5HasMore
                || pickHasMore || picksTemplateHasMore || dailyGameHasMore;

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

    private FeedItemDTO toDiaryFeedItem(DiaryEntry entry, boolean likedByMe, List<UserPreviewDTO> watchedWith,
            CommentPreviewData commentPreview) {
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
                commentsCount(commentPreview),
                recentComments(commentPreview),
                watchedWith,
                null,
                null,
                null,
                entry.getCreatedAt(),
                null);
    }

    private FeedItemDTO toDroppedFeedItem(DroppedEntry entry, boolean likedByMe, CommentPreviewData commentPreview) {
        return new FeedItemDTO(
                FeedEventType.DROPPED,
                entry.getId(),
                userMapper.userToUserPreviewDto(entry.getUser()),
                contentMapper.contentToContentRefDto(entry.getContent()),
                null,
                null,
                entry.getComment(),
                entry.getLikesCount(),
                likedByMe,
                commentsCount(commentPreview),
                recentComments(commentPreview),
                null,
                null,
                null,
                null,
                entry.getCreatedAt(),
                null);
    }

    private static Integer commentsCount(CommentPreviewData commentPreview) {
        return commentPreview == null ? 0 : Math.toIntExact(commentPreview.commentsCount());
    }

    private static List<CommentResponseDTO> recentComments(CommentPreviewData commentPreview) {
        return commentPreview == null ? List.of() : commentPreview.recentComments();
    }

    private Map<Top5Key, List<Top5EntryResponseDTO>> loadCurrentTop5Previews(List<Top5Entry> top5Entries) {
        if (top5Entries.isEmpty()) {
            return Map.of();
        }

        List<UUID> userIds = top5Entries.stream()
                .map(entry -> entry.getUser().getId())
                .distinct()
                .toList();
        Map<ContentType, List<UUID>> userIdsByType = new LinkedHashMap<>();
        for (Top5Entry entry : top5Entries) {
            List<UUID> typeUserIds = userIdsByType.computeIfAbsent(entry.getType(), ignored -> new ArrayList<>());
            UUID userId = entry.getUser().getId();
            if (!typeUserIds.contains(userId)) {
                typeUserIds.add(userId);
            }
        }
        List<Top5Entry> currentEntries = new ArrayList<>();
        userIdsByType.forEach((type, typeUserIds) -> currentEntries.addAll(
                top5EntryRepository.findCurrentPreviewsByUserIdsAndType(typeUserIds, type)));
        List<UserContentPosterService.UserContentPosterKey> posterKeys = currentEntries.stream()
                .map(entry -> new UserContentPosterService.UserContentPosterKey(
                        entry.getUser().getId(), entry.getContent().getId()))
                .distinct()
                .toList();
        Map<UserContentPosterService.UserContentPosterKey, String> customPosters =
                userContentPosterService.findByUserAndContentPairs(posterKeys);

        Map<Top5Key, List<Top5EntryResponseDTO>> previewsByKey = new LinkedHashMap<>();
        for (Top5Entry entry : currentEntries) {
            Top5Key key = top5Key(entry);
            UserContentPosterService.UserContentPosterKey posterKey =
                    new UserContentPosterService.UserContentPosterKey(key.userId(), entry.getContent().getId());
            Top5EntryResponseDTO preview = top5EntryMapper.top5EntryToResponseDto(entry)
                    .withCustomPosterUrl(customPosters.get(posterKey));
            previewsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(preview);
        }
        return previewsByKey;
    }

    private Top5Key top5Key(Top5Entry entry) {
        return new Top5Key(entry.getUser().getId(), entry.getType());
    }

    private FeedItemDTO toTop5FeedItem(Top5Entry entry, List<Top5EntryResponseDTO> top5) {
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
                null,
                null,
                null,
                entry.getCreatedAt(),
                top5);
    }

    private FeedItemDTO toPickFeedItem(Pick entry, PickPreviewDTO pick, PicksTemplatePreviewDTO picksTemplate) {
        return new FeedItemDTO(
                FeedEventType.PICK_CREATED,
                entry.getId(),
                userMapper.userToUserPreviewDto(entry.getUser()),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                pick,
                picksTemplate,
                null,
                entry.getCreatedAt());
    }

    private FeedItemDTO toPicksTemplateFeedItem(PicksTemplate entry, PicksTemplatePreviewDTO picksTemplate) {
        return new FeedItemDTO(
                FeedEventType.PICKS_TEMPLATE_CREATED,
                entry.getId(),
                userMapper.userToUserPreviewDto(entry.getCreator()),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                picksTemplate,
                null,
                entry.getCreatedAt());
    }

    private FeedItemDTO toDailyGameFeedItem(UserDailyGameResult result) {
        DailyChallenge challenge = result.getDailyChallenge();
        DailyGameResultPreviewDTO preview = new DailyGameResultPreviewDTO(
                challenge.getChallengeDate(),
                challenge.getGameType(),
                challenge.getTargetKind(),
                challenge.getGameType().maxAttempts(),
                toViewStatus(result.getStatus()),
                result.getAttemptsUsed(),
                result.getScore(),
                dailyChallengeResponseAssembler.toAnswer(challenge));
        return new FeedItemDTO(
                FeedEventType.DAILY_GAME_RESULT,
                result.getId(),
                userMapper.userToUserPreviewDto(result.getUser()),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                preview,
                result.getSharedAt());
    }

    private DailyGameViewStatus toViewStatus(DailyGameResultStatus status) {
        return switch (status) {
            case IN_PROGRESS -> DailyGameViewStatus.IN_PROGRESS;
            case COMPLETED -> DailyGameViewStatus.COMPLETED;
            case FAILED -> DailyGameViewStatus.FAILED;
        };
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

    private record Top5Key(UUID userId, ContentType type) {
    }

    private record FeedCandidate(LocalDateTime createdAt, UUID id, FeedItemDTO item) {
    }
}
