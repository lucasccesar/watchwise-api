package com.watchwise.watchwise_api.feed.service.impl;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.service.CommentPreviewData;
import com.watchwise.watchwise_api.comment.service.impl.CommentPreviewAssembler;
import com.watchwise.watchwise_api.common.dto.CursorPageResponseDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
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
import com.watchwise.watchwise_api.feed.dto.FeedItemViewDTO;
import com.watchwise.watchwise_api.feed.dto.FeedPageViewDTO;
import com.watchwise.watchwise_api.feed.dto.FeedTop5PreviewDTO;
import com.watchwise.watchwise_api.feed.service.FeedService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
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
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FeedServiceImpl implements FeedService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;
    private static final String CURSOR_SEPARATOR = "|";
    private static final String DEFAULT_LANGUAGE = "en-US";
    private static final String DEFAULT_REGION = "US";
    private static final Set<ContentCardFieldSet> FEED_CARD_FIELDS = Set.of(ContentCardFieldSet.BASIC_METADATA);

    private final FollowerRepository followerRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final ContentReviewRepository contentReviewRepository;
    private final ContentRepository contentRepository;
    private final LikeRepository likeRepository;
    private final DroppedEntryRepository droppedEntryRepository;
    private final Top5EntryRepository top5EntryRepository;
    private final UserDailyGameResultRepository dailyGameResultRepository;
    private final PickRepository pickRepository;
    private final PicksTemplateRepository picksTemplateRepository;
    private final WatchCompanionRepository watchCompanionRepository;
    private final LikeService likeService;
    private final ContentMapper contentMapper;
    private final ContentCardAssembler contentCardAssembler;
    private final Top5EntryMapper top5EntryMapper;
    private final UserContentPosterService userContentPosterService;
    private final UserMapper userMapper;
    private final DailyChallengeResponseAssembler dailyChallengeResponseAssembler;
    private final PickPreviewAssembler pickPreviewAssembler;
    private final PicksTemplatePreviewAssembler picksTemplatePreviewAssembler;
    private final CommentPreviewAssembler commentPreviewAssembler;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponseDTO<FeedItemDTO> getFeed(UUID userId, String cursor, Integer size) {
        List<UUID> followedIds = followerRepository.findVisibleFollowedIdsByFollowerId(userId);
        followedIds = followedIds == null ? List.of() : followedIds;
        return enrichSelectedPage(userId, selectRawFeedPage(userId, cursor, size, followedIds));
    }

    @Override
    @Transactional(readOnly = true)
    public FeedPageViewDTO getFeedView(UUID userId, String cursor, Integer size) {
        List<UUID> followedIds = followerRepository.findVisibleFollowedIdsByFollowerId(userId);
        followedIds = followedIds == null ? List.of() : followedIds;
        RawFeedPage rawPage = selectRawFeedPage(userId, cursor, size, followedIds);
        CursorPageResponseDTO<FeedItemDTO> rawFeed = enrichSelectedPage(userId, rawPage);
        Map<ContentCoordinate, ContentCardDTO> cards = assembleCards(rawFeed.content(), userId);
        List<FeedItemViewDTO> content = rawFeed.content().stream()
                .map(item -> toFeedItemView(item, cards))
                .toList();
        return new FeedPageViewDTO(content, rawFeed.size(), rawFeed.nextCursor(), rawFeed.hasNext(),
                followedIds.size(), loadFollowedUserPreviews(followedIds));
    }

    private RawFeedPage selectRawFeedPage(UUID userId, String cursor, Integer size, List<UUID> followedIds) {
        int effectiveSize = resolveSize(size);
        FeedCursor decodedCursor = decodeCursor(cursor);
        LocalDateTime cursorCreatedAt = decodedCursor == null ? null : decodedCursor.createdAt();
        UUID cursorId = decodedCursor == null ? null : decodedCursor.id();

        if (followedIds.isEmpty()) {
            return new RawFeedPage(List.of(), effectiveSize, null, false);
        }

        PageRequest fetchLimit = PageRequest.of(0, effectiveSize + 1);
        List<DiaryEntry> diaryRaw = diaryEntryRepository.findFeedCandidates(
                followedIds, cursorCreatedAt, cursorId, fetchLimit);
        List<ContentReview> reviewRaw = contentReviewRepository.findFeedCandidates(
                followedIds, userId, cursorCreatedAt, cursorId, fetchLimit);
        List<DroppedEntry> droppedRaw = droppedEntryRepository.findFeedCandidates(
                followedIds, cursorCreatedAt, cursorId, fetchLimit);
        List<Top5Entry> top5Raw = top5EntryRepository.findFeedCandidates(
                followedIds, cursorCreatedAt, cursorId, fetchLimit);
        List<Pick> pickRaw = pickRepository.findFeedCandidates(
                followedIds, userId, cursorCreatedAt, cursorId, fetchLimit);
        List<PicksTemplate> picksTemplateRaw = picksTemplateRepository.findFeedCandidates(
                followedIds, cursorCreatedAt, cursorId, fetchLimit);
        List<UserDailyGameResult> dailyGameRaw = dailyGameResultRepository.findFeedCandidates(
                followedIds, cursorCreatedAt, cursorId, fetchLimit);

        boolean diaryHasMore = diaryRaw.size() > effectiveSize;
        boolean reviewHasMore = reviewRaw.size() > effectiveSize;
        boolean droppedHasMore = droppedRaw.size() > effectiveSize;
        boolean top5HasMore = top5Raw.size() > effectiveSize;
        boolean pickHasMore = pickRaw.size() > effectiveSize;
        boolean picksTemplateHasMore = picksTemplateRaw.size() > effectiveSize;
        boolean dailyGameHasMore = dailyGameRaw.size() > effectiveSize;

        List<RawFeedCandidate> candidates = new ArrayList<>();
        trim(diaryRaw, effectiveSize).forEach(entry -> candidates.add(new RawFeedCandidate(
                entry.getCreatedAt(), entry.getId(), FeedEventType.DIARY_ENTRY, entry)));
        trim(reviewRaw, effectiveSize).forEach(entry -> candidates.add(new RawFeedCandidate(
                reviewPublicationTime(entry), entry.getId(), FeedEventType.CONTENT_REVIEW, entry)));
        trim(droppedRaw, effectiveSize).forEach(entry -> candidates.add(new RawFeedCandidate(
                entry.getCreatedAt(), entry.getId(), FeedEventType.DROPPED, entry)));
        trim(top5Raw, effectiveSize).forEach(entry -> candidates.add(new RawFeedCandidate(
                entry.getCreatedAt(), entry.getId(), FeedEventType.TOP5_UPDATE, entry)));
        trim(pickRaw, effectiveSize).forEach(entry -> candidates.add(new RawFeedCandidate(
                entry.getCreatedAt(), entry.getId(), FeedEventType.PICK_CREATED, entry)));
        trim(picksTemplateRaw, effectiveSize).forEach(entry -> candidates.add(new RawFeedCandidate(
                entry.getCreatedAt(), entry.getId(), FeedEventType.PICKS_TEMPLATE_CREATED, entry)));
        trim(dailyGameRaw, effectiveSize).forEach(entry -> candidates.add(new RawFeedCandidate(
                entry.getSharedAt(), entry.getId(), FeedEventType.DAILY_GAME_RESULT, entry)));

        candidates.sort(Comparator.comparing(RawFeedCandidate::createdAt).reversed()
                .thenComparing(candidate -> candidate.id().toString(), Comparator.reverseOrder()));

        boolean hasMoreBeyondPage = candidates.size() > effectiveSize;
        List<RawFeedCandidate> page = hasMoreBeyondPage
                ? candidates.subList(0, effectiveSize) : candidates;
        boolean hasNext = hasMoreBeyondPage || diaryHasMore || reviewHasMore || droppedHasMore || top5HasMore
                || pickHasMore || picksTemplateHasMore || dailyGameHasMore;
        String nextCursor = hasNext && !page.isEmpty()
                ? encodeCursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id()) : null;
        return new RawFeedPage(page, effectiveSize, nextCursor, hasNext);
    }

    private CursorPageResponseDTO<FeedItemDTO> enrichSelectedPage(UUID userId, RawFeedPage rawPage) {
        if (rawPage.candidates().isEmpty()) {
            return new CursorPageResponseDTO<>(List.of(), rawPage.size(), rawPage.nextCursor(), rawPage.hasNext());
        }
        List<DiaryEntry> diaryEntries = sources(rawPage.candidates(), FeedEventType.DIARY_ENTRY, DiaryEntry.class);
        List<ContentReview> reviewEntries = sources(rawPage.candidates(), FeedEventType.CONTENT_REVIEW, ContentReview.class);
        List<DroppedEntry> droppedEntries = sources(rawPage.candidates(), FeedEventType.DROPPED, DroppedEntry.class);
        List<Top5Entry> top5Entries = sources(rawPage.candidates(), FeedEventType.TOP5_UPDATE, Top5Entry.class);
        List<Pick> pickEntries = sources(rawPage.candidates(), FeedEventType.PICK_CREATED, Pick.class);
        List<PicksTemplate> picksTemplateEntries = sources(
                rawPage.candidates(), FeedEventType.PICKS_TEMPLATE_CREATED, PicksTemplate.class);
        List<UserDailyGameResult> dailyGameEntries = sources(
                rawPage.candidates(), FeedEventType.DAILY_GAME_RESULT, UserDailyGameResult.class);
        Set<UUID> viewerTerminalDailyChallengeIds = loadViewerTerminalDailyChallengeIds(userId, dailyGameEntries);

        Map<Top5Key, List<Top5EntryResponseDTO>> currentTop5ByKey = loadCurrentTop5Previews(top5Entries);
        Set<UUID> likedDiaryEntryIds = diaryEntries.isEmpty() ? Set.of() : likeService.getLikedDiaryEntryIds(
                userId, diaryEntries.stream().map(DiaryEntry::getId).toList());
        Set<UUID> likedDroppedEntryIds = droppedEntries.isEmpty() ? Set.of() : likeService.getLikedDroppedEntryIds(
                userId, droppedEntries.stream().map(DroppedEntry::getId).toList());
        Map<UUID, List<UserPreviewDTO>> watchedWithByEntryId = diaryEntries.isEmpty() ? Map.of()
                : watchCompanionRepository.findByDiaryEntryIdIn(diaryEntries.stream().map(DiaryEntry::getId).toList()).stream()
                        .collect(Collectors.groupingBy(wc -> wc.getDiaryEntry().getId(),
                                Collectors.mapping(wc -> userMapper.userToUserPreviewDto(wc.getUser()), Collectors.toList())));
        Map<UUID, CommentPreviewData> diaryCommentPreviews = diaryEntries.isEmpty() ? Map.of()
                : commentPreviewAssembler.assembleDiaryEntryPreviews(
                        diaryEntries.stream().map(DiaryEntry::getId).toList(), userId);
        ReviewFeedContext reviewContext = loadReviewFeedContext(userId, reviewEntries);
        Map<UUID, CommentPreviewData> droppedCommentPreviews = droppedEntries.isEmpty() ? Map.of()
                : commentPreviewAssembler.assembleDroppedEntryPreviews(
                        droppedEntries.stream().map(DroppedEntry::getId).toList(), userId);
        Map<UUID, PickPreviewDTO> pickPreviews = pickEntries.isEmpty()
                ? Map.of() : pickPreviewAssembler.assembleForFeed(pickEntries, userId);
        Map<UUID, PicksTemplate> templatesById = new LinkedHashMap<>();
        pickEntries.forEach(pick -> templatesById.put(pick.getPicksTemplate().getId(), pick.getPicksTemplate()));
        picksTemplateEntries.forEach(template -> templatesById.put(template.getId(), template));
        Map<UUID, PicksTemplatePreviewDTO> picksTemplatePreviews = templatesById.isEmpty()
                ? Map.of() : picksTemplatePreviewAssembler.assembleForFeed(templatesById.values(), userId);

        List<FeedItemDTO> content = rawPage.candidates().stream()
                .map(candidate -> toEnrichedFeedItem(candidate, reviewContext,
                        likedDiaryEntryIds, likedDroppedEntryIds,
                        watchedWithByEntryId, diaryCommentPreviews, droppedCommentPreviews, currentTop5ByKey,
                        pickPreviews, picksTemplatePreviews, viewerTerminalDailyChallengeIds))
                .toList();
        return new CursorPageResponseDTO<>(content, rawPage.size(), rawPage.nextCursor(), rawPage.hasNext());
    }

    private <T> List<T> sources(List<RawFeedCandidate> candidates, FeedEventType eventType, Class<T> sourceType) {
        return candidates.stream()
                .filter(candidate -> candidate.eventType() == eventType)
                .map(RawFeedCandidate::source)
                .map(sourceType::cast)
                .toList();
    }

    private ReviewFeedContext loadReviewFeedContext(UUID viewerId, List<ContentReview> reviews) {
        if (reviews.isEmpty()) {
            return new ReviewFeedContext(Map.of(), Map.of(), Map.of(), Set.of(), Map.of());
        }
        List<UUID> reviewIds = reviews.stream().map(ContentReview::getId).toList();
        Map<UUID, User> users = userRepository.findAllById(
                        reviews.stream().map(ContentReview::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        Map<UUID, Content> contents = contentRepository.findAllById(
                        reviews.stream().map(ContentReview::getContentId).distinct().toList()).stream()
                .collect(Collectors.toMap(Content::getId, content -> content));
        Map<UUID, Long> likes = likeRepository.countByContentReviewIdIn(reviewIds).stream()
                .collect(Collectors.toMap(LikeRepository.ContentReviewLikeCount::getReviewId,
                        LikeRepository.ContentReviewLikeCount::getCount));
        Set<UUID> liked = likeRepository.findLikedContentReviewIds(viewerId, reviewIds);
        Map<UUID, CommentPreviewData> comments = commentPreviewAssembler.assembleContentReviewPreviews(
                reviewIds, viewerId);
        return new ReviewFeedContext(users, contents, likes, liked, comments);
    }

    private LocalDateTime reviewPublicationTime(ContentReview review) {
        if (review.getSourcePublishedAt() != null) {
            return LocalDateTime.ofInstant(review.getSourcePublishedAt(), ZoneOffset.UTC);
        }
        return review.getSourcePublishedOn().atStartOfDay();
    }

    private FeedItemDTO toEnrichedFeedItem(
            RawFeedCandidate candidate,
            ReviewFeedContext reviewContext,
            Set<UUID> likedDiaryEntryIds,
            Set<UUID> likedDroppedEntryIds,
            Map<UUID, List<UserPreviewDTO>> watchedWithByEntryId,
            Map<UUID, CommentPreviewData> diaryCommentPreviews,
            Map<UUID, CommentPreviewData> droppedCommentPreviews,
            Map<Top5Key, List<Top5EntryResponseDTO>> currentTop5ByKey,
            Map<UUID, PickPreviewDTO> pickPreviews,
            Map<UUID, PicksTemplatePreviewDTO> picksTemplatePreviews,
            Set<UUID> viewerTerminalDailyChallengeIds) {
        return switch (candidate.eventType()) {
            case CONTENT_REVIEW -> toReviewFeedItem((ContentReview) candidate.source(), reviewContext);
            case DIARY_ENTRY -> {
                DiaryEntry entry = (DiaryEntry) candidate.source();
                yield toDiaryFeedItem(entry, likedDiaryEntryIds.contains(entry.getId()),
                        watchedWithByEntryId.getOrDefault(entry.getId(), List.of()),
                        diaryCommentPreviews.get(entry.getId()));
            }
            case DROPPED -> {
                DroppedEntry entry = (DroppedEntry) candidate.source();
                yield toDroppedFeedItem(entry, likedDroppedEntryIds.contains(entry.getId()),
                        droppedCommentPreviews.get(entry.getId()));
            }
            case TOP5_UPDATE -> {
                Top5Entry entry = (Top5Entry) candidate.source();
                yield toTop5FeedItem(entry, currentTop5ByKey.getOrDefault(top5Key(entry), List.of()));
            }
            case PICK_CREATED -> {
                Pick entry = (Pick) candidate.source();
                yield toPickFeedItem(entry, pickPreviews.get(entry.getId()),
                        picksTemplatePreviews.get(entry.getPicksTemplate().getId()));
            }
            case PICKS_TEMPLATE_CREATED -> {
                PicksTemplate entry = (PicksTemplate) candidate.source();
                yield toPicksTemplateFeedItem(entry, picksTemplatePreviews.get(entry.getId()));
            }
            case DAILY_GAME_RESULT -> {
                UserDailyGameResult result = (UserDailyGameResult) candidate.source();
                yield toDailyGameFeedItem(result,
                        viewerTerminalDailyChallengeIds.contains(result.getDailyChallenge().getId()));
            }
        };
    }

    private List<UserPreviewDTO> loadFollowedUserPreviews(List<UUID> followedIds) {
        if (followedIds == null || followedIds.isEmpty() || userRepository == null) {
            return List.of();
        }
        Map<UUID, User> usersById = new LinkedHashMap<>();
        Iterable<User> users = userRepository.findAllById(followedIds);
        if (users != null) {
            for (User user : users) {
                if (user != null && user.getId() != null) {
                    usersById.putIfAbsent(user.getId(), user);
                }
            }
        }
        if (userMapper == null) {
            return List.of();
        }
        return followedIds.stream()
                .map(usersById::get)
                .filter(Objects::nonNull)
                .map(userMapper::userToUserPreviewDto)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<ContentCardSpec> cardSpecs(List<FeedItemDTO> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<ContentCoordinate, ContentCardSpec> specsByCoordinate = new LinkedHashMap<>();
        for (FeedItemDTO item : items) {
            if (item == null) {
                continue;
            }
            addCardSpec(specsByCoordinate, item.content());
            if (item.top5() != null) {
                item.top5().stream()
                        .map(Top5EntryResponseDTO::content)
                        .forEach(content -> addCardSpec(specsByCoordinate, content));
            }
            if (item.pick() != null) {
                safeAnsweredCategories(item.pick()).stream()
                        .filter(Objects::nonNull)
                        .map(answered -> answered.target())
                        .filter(Objects::nonNull)
                        .forEach(target -> {
                            addCardSpec(specsByCoordinate, target.content());
                            addCardSpec(specsByCoordinate, target.contextContent());
                        });
            }
        }
        return List.copyOf(specsByCoordinate.values());
    }

    private void addCardSpec(Map<ContentCoordinate, ContentCardSpec> specsByCoordinate, ContentRefDTO content) {
        if (content == null || content.type() == null) {
            return;
        }
        ContentCoordinate coordinate = new ContentCoordinate(content.type(), content.tmdbId(), content.seriesTmdbId(),
                content.seasonNumber(), content.episodeNumber());
        specsByCoordinate.putIfAbsent(coordinate,
                new ContentCardSpec(coordinate, null, null, null, content.runtimeMinutes()));
    }

    private Map<ContentCoordinate, ContentCardDTO> assembleCards(List<FeedItemDTO> items, UUID viewerId) {
        List<ContentCardSpec> specs = cardSpecs(items);
        if (specs.isEmpty() || contentCardAssembler == null) {
            return Map.of();
        }
        User viewer = userRepository == null ? null : userRepository.findById(viewerId).orElse(null);
        String language = viewer == null || viewer.getPreferredLanguage() == null
                ? DEFAULT_LANGUAGE : viewer.getPreferredLanguage();
        String region = viewer == null || viewer.getPreferredRegion() == null
                ? DEFAULT_REGION : viewer.getPreferredRegion();
        Map<ContentCoordinate, ContentCardDTO> cards = contentCardAssembler.assemble(
                specs, new ContentCardContext(language, region, null, null), FEED_CARD_FIELDS);
        return cards == null ? Map.of() : cards;
    }

    private FeedItemViewDTO toFeedItemView(
            FeedItemDTO item, Map<ContentCoordinate, ContentCardDTO> cards) {
        ContentCardDTO contentCard = cardFor(item.content(), cards);
        FeedTop5PreviewDTO top5Preview = item.top5() == null ? null
                : new FeedTop5PreviewDTO(item.top5Type(), item.top5(), item.top5().stream()
                        .map(Top5EntryResponseDTO::content)
                        .map(content -> cardFor(content, cards))
                        .toList());
        List<ContentCardDTO> pickTargetCards = item.pick() == null ? null
                : safeAnsweredCategories(item.pick()).stream()
                        .filter(Objects::nonNull)
                        .map(answered -> answered.target())
                        .filter(Objects::nonNull)
                        .flatMap(target -> java.util.stream.Stream.of(target.content(), target.contextContent()))
                        .filter(Objects::nonNull)
                        .map(content -> cardFor(content, cards))
                        .toList();
        return new FeedItemViewDTO(item, contentCard, top5Preview, pickTargetCards);
    }

    private ContentCardDTO cardFor(ContentRefDTO content, Map<ContentCoordinate, ContentCardDTO> cards) {
        if (content == null || content.type() == null || cards == null || cards.isEmpty()) {
            return null;
        }
        ContentCoordinate coordinate = new ContentCoordinate(content.type(), content.tmdbId(), content.seriesTmdbId(),
                content.seasonNumber(), content.episodeNumber());
        return cards.get(coordinate);
    }

    private List<com.watchwise.watchwise_api.pick.dto.PickAnsweredCategoryPreviewDTO> safeAnsweredCategories(
            PickPreviewDTO pick) {
        return pick.answeredCategories() == null ? List.of() : pick.answeredCategories();
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

    private FeedItemDTO toReviewFeedItem(ContentReview review, ReviewFeedContext context) {
        CommentPreviewData comments = context.comments().get(review.getId());
        return new FeedItemDTO(
                FeedEventType.CONTENT_REVIEW, review.getId(),
                userMapper.userToUserPreviewDto(context.users().get(review.getUserId())),
                contentMapper.contentToContentRefDto(context.contents().get(review.getContentId())),
                null, review.getScoreAtPublication(), review.getComment(),
                Math.toIntExact(context.likes().getOrDefault(review.getId(), 0L)),
                context.liked().contains(review.getId()),
                commentsCount(comments), recentComments(comments), List.of(),
                null, null, null, reviewPublicationTime(review), null);
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

    private Set<UUID> loadViewerTerminalDailyChallengeIds(
            UUID viewerId, Collection<UserDailyGameResult> dailyGameEntries) {
        List<UUID> challengeIds = dailyGameEntries.stream()
                .map(result -> result.getDailyChallenge().getId())
                .distinct()
                .toList();
        if (challengeIds.isEmpty()) {
            return Set.of();
        }
        return dailyGameResultRepository.findByUserIdAndDailyChallengeIdIn(viewerId, challengeIds).stream()
                .filter(result -> result.getStatus() == DailyGameResultStatus.COMPLETED
                        || result.getStatus() == DailyGameResultStatus.FAILED)
                .map(result -> result.getDailyChallenge().getId())
                .collect(Collectors.toSet());
    }

    private FeedItemDTO toDailyGameFeedItem(UserDailyGameResult result, boolean viewerCanSeeAnswer) {
        DailyChallenge challenge = result.getDailyChallenge();
        DailyGameResultPreviewDTO preview = new DailyGameResultPreviewDTO(
                challenge.getChallengeDate(),
                challenge.getGameType(),
                challenge.getTargetKind(),
                challenge.getGameType().maxAttempts(),
                toViewStatus(result.getStatus()),
                result.getAttemptsUsed(),
                result.getScore(),
                viewerCanSeeAnswer ? dailyChallengeResponseAssembler.toAnswer(challenge) : null);
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
        return encodeCursor(lastItem.createdAt(), lastItem.id());
    }

    private String encodeCursor(LocalDateTime createdAt, UUID id) {
        String raw = createdAt + CURSOR_SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record FeedCursor(LocalDateTime createdAt, UUID id) {
    }

    private record Top5Key(UUID userId, ContentType type) {
    }

    private record FeedCandidate(LocalDateTime createdAt, UUID id, FeedItemDTO item) {
    }

    private record RawFeedPage(
            List<RawFeedCandidate> candidates,
            int size,
            String nextCursor,
            boolean hasNext) {
    }

    private record RawFeedCandidate(
            LocalDateTime createdAt,
            UUID id,
            FeedEventType eventType,
            Object source) {
    }

    private record ReviewFeedContext(
            Map<UUID, User> users,
            Map<UUID, Content> contents,
            Map<UUID, Long> likes,
            Set<UUID> liked,
            Map<UUID, CommentPreviewData> comments) {
    }
}
