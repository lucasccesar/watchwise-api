package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.notification.dto.NotificationTargetPreviewDTO;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class NotificationTargetPreviewReader {

    private final CommentRepository commentRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final ContentReviewRepository contentReviewRepository;
    private final ContentRepository contentRepository;
    private final DroppedEntryRepository droppedEntryRepository;
    private final UserListRepository userListRepository;
    private final PickRepository pickRepository;
    private final PicksTemplateRepository picksTemplateRepository;
    private final ContentCardAssembler contentCardAssembler;

    public NotificationTargetPreviewReader(
            CommentRepository commentRepository,
            DiaryEntryRepository diaryEntryRepository,
            ContentReviewRepository contentReviewRepository,
            ContentRepository contentRepository,
            DroppedEntryRepository droppedEntryRepository,
            UserListRepository userListRepository,
            PickRepository pickRepository,
            PicksTemplateRepository picksTemplateRepository,
            ContentCardAssembler contentCardAssembler) {
        this.commentRepository = commentRepository;
        this.diaryEntryRepository = diaryEntryRepository;
        this.contentReviewRepository = contentReviewRepository;
        this.contentRepository = contentRepository;
        this.droppedEntryRepository = droppedEntryRepository;
        this.userListRepository = userListRepository;
        this.pickRepository = pickRepository;
        this.picksTemplateRepository = picksTemplateRepository;
        this.contentCardAssembler = contentCardAssembler;
    }

    public Map<NotificationTargetKey, NotificationTargetPreviewDTO> read(
            Collection<NotificationTargetKey> keys,
            ContentCardContext context) {
        Objects.requireNonNull(context, "context is required");
        Map<NotificationTargetKey, NotificationTargetKey> distinctKeys = distinctKeys(keys);
        if (distinctKeys.isEmpty()) {
            return Map.of();
        }

        Map<NotificationTargetType, Set<UUID>> idsByType = idsByType(distinctKeys.keySet());
        Map<UUID, Comment> comments = loadComments(idsByType.get(NotificationTargetType.COMMENT));
        Map<UUID, DiaryEntry> diaryEntries = loadDiaryEntries(idsByType.get(NotificationTargetType.DIARY_ENTRY));
        Map<UUID, ContentReview> contentReviews = loadContentReviews(
                idsByType.get(NotificationTargetType.CONTENT_REVIEW));
        Map<UUID, DroppedEntry> droppedEntries = loadDroppedEntries(idsByType.get(NotificationTargetType.DROPPED_ENTRY));
        Map<UUID, UserList> lists = loadLists(idsByType.get(NotificationTargetType.USER_LIST));
        Map<UUID, Pick> picks = loadPicks(idsByType.get(NotificationTargetType.PICK));
        Map<UUID, PicksTemplate> templates = loadTemplates(idsByType.get(NotificationTargetType.PICKS_TEMPLATE));
        Map<UUID, Content> reviewContents = loadReviewContents(contentReviews.values());
        Map<ContentCoordinate, ContentCardDTO> cards = assembleContentCards(
                diaryEntries.values(), droppedEntries.values(),
                reviewContents.values(), context);

        Map<NotificationTargetKey, NotificationTargetPreviewDTO> previews = new LinkedHashMap<>();
        for (NotificationTargetKey key : distinctKeys.keySet()) {
            previews.put(key, preview(key, comments, diaryEntries, contentReviews,
                    droppedEntries, lists, picks, templates, reviewContents, cards));
        }
        return Collections.unmodifiableMap(previews);
    }

    private Map<NotificationTargetKey, NotificationTargetKey> distinctKeys(Collection<NotificationTargetKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        return keys.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Function.identity(), Function.identity(), (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private Map<NotificationTargetType, Set<UUID>> idsByType(Collection<NotificationTargetKey> keys) {
        Map<NotificationTargetType, Set<UUID>> idsByType = new EnumMap<>(NotificationTargetType.class);
        for (NotificationTargetKey key : keys) {
            if (key.targetType() == null || key.targetId() == null) {
                continue;
            }
            idsByType.computeIfAbsent(key.targetType(), ignored -> new LinkedHashSet<>()).add(key.targetId());
        }
        return idsByType;
    }

    private Map<UUID, Comment> loadComments(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return byId(commentRepository.findByIdInWithUser(ids), Comment::getId);
    }

    private Map<UUID, DiaryEntry> loadDiaryEntries(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return byId(diaryEntryRepository.findByIdInWithContentAndUser(ids), DiaryEntry::getId);
    }

    private Map<UUID, ContentReview> loadContentReviews(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return byId(contentReviewRepository.findAllById(ids), ContentReview::getId);
    }

    private Map<UUID, DroppedEntry> loadDroppedEntries(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return byId(droppedEntryRepository.findByIdInWithUserAndContent(ids), DroppedEntry::getId);
    }

    private Map<UUID, UserList> loadLists(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return byId(userListRepository.findByIdInWithUser(ids), UserList::getId);
    }

    private Map<UUID, Pick> loadPicks(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return byId(pickRepository.findByIdInWithPicksTemplate(ids), Pick::getId);
    }

    private Map<UUID, PicksTemplate> loadTemplates(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return byId(picksTemplateRepository.findByIdInWithCreator(ids), PicksTemplate::getId);
    }

    private Map<ContentCoordinate, ContentCardDTO> assembleContentCards(
            Collection<DiaryEntry> diaryEntries,
            Collection<DroppedEntry> droppedEntries,
            Collection<Content> reviewContents,
            ContentCardContext context) {
        Map<ContentCoordinate, Content> contentByCoordinate = new LinkedHashMap<>();
        addContents(diaryEntries, DiaryEntry::getContent, contentByCoordinate);
        addContents(droppedEntries, DroppedEntry::getContent, contentByCoordinate);
        for (Content content : reviewContents) {
            contentByCoordinate.putIfAbsent(ContentCoordinate.from(content), content);
        }
        if (contentByCoordinate.isEmpty()) {
            return Map.of();
        }

        List<ContentCardSpec> specs = contentByCoordinate.keySet().stream()
                .map(coordinate -> new ContentCardSpec(coordinate, null, null, null, null))
                .toList();
        Map<ContentCoordinate, ContentCardDTO> cards = contentCardAssembler.assemble(
                specs, context, ContentCardFieldSet.BASIC_METADATA);
        return cards == null ? Map.of() : cards;
    }

    private <T> void addContents(
            Collection<T> entries,
            Function<T, Content> contentExtractor,
            Map<ContentCoordinate, Content> contentByCoordinate) {
        for (T entry : entries) {
            Content content = contentExtractor.apply(entry);
            if (content != null) {
                contentByCoordinate.putIfAbsent(ContentCoordinate.from(content), content);
            }
        }
    }

    private NotificationTargetPreviewDTO preview(
            NotificationTargetKey key,
            Map<UUID, Comment> comments,
            Map<UUID, DiaryEntry> diaryEntries,
            Map<UUID, ContentReview> contentReviews,
            Map<UUID, DroppedEntry> droppedEntries,
            Map<UUID, UserList> lists,
            Map<UUID, Pick> picks,
            Map<UUID, PicksTemplate> templates,
            Map<UUID, Content> reviewContents,
            Map<ContentCoordinate, ContentCardDTO> cards) {
        if (key.targetType() == null || key.targetId() == null) {
            return unavailable(key);
        }
        return switch (key.targetType()) {
            case COMMENT -> comments.containsKey(key.targetId())
                    ? new NotificationTargetPreviewDTO(key.targetType(), key.targetId(), "Comment",
                    comments.get(key.targetId()).getText(), null, null, null, ContentPreviewStatus.AVAILABLE)
                    : unavailable(key);
            case DIARY_ENTRY -> diaryEntries.containsKey(key.targetId())
                    ? diaryPreview(key, diaryEntries.get(key.targetId()), cards)
                    : unavailable(key);
            case CONTENT_REVIEW -> contentReviewPreview(
                    key, contentReviews.get(key.targetId()), reviewContents, cards);
            case DROPPED_ENTRY -> droppedPreview(key, droppedEntries.get(key.targetId()), cards);
            case USER_LIST -> lists.containsKey(key.targetId())
                    ? new NotificationTargetPreviewDTO(key.targetType(), key.targetId(),
                    lists.get(key.targetId()).getName(), lists.get(key.targetId()).getDescription(),
                    null, null, null, ContentPreviewStatus.AVAILABLE)
                    : unavailable(key);
            case PICK -> pickPreview(key, picks.get(key.targetId()));
            case PICKS_TEMPLATE -> templatePreview(key, templates.get(key.targetId()));
            case REPORT_CASE -> new NotificationTargetPreviewDTO(
                    key.targetType(), key.targetId(), "Report", null, null, null, null,
                    ContentPreviewStatus.AVAILABLE);
        };
    }

    private NotificationTargetPreviewDTO diaryPreview(
            NotificationTargetKey key,
            DiaryEntry diaryEntry,
            Map<ContentCoordinate, ContentCardDTO> cards) {
        if (diaryEntry == null) {
            return unavailable(key);
        }
        ContentCardDTO card = contentCard(diaryEntry.getContent(), cards);
        return new NotificationTargetPreviewDTO(key.targetType(), key.targetId(), "Review", diaryEntry.getComment(),
                diaryEntry.getScore(), null, card, status(card));
    }

    private NotificationTargetPreviewDTO contentReviewPreview(
            NotificationTargetKey key,
            ContentReview review,
            Map<UUID, Content> reviewContents,
            Map<ContentCoordinate, ContentCardDTO> cards) {
        if (review == null) {
            return unavailable(key);
        }
        Content content = reviewContents.get(review.getContentId());
        ContentCardDTO card = content == null ? null : contentCard(content, cards);
        return new NotificationTargetPreviewDTO(key.targetType(), key.targetId(), "Review", review.getComment(),
                review.getScoreAtPublication(), null, card, status(card));
    }

    private Map<UUID, Content> loadReviewContents(Collection<ContentReview> contentReviews) {
        Set<UUID> contentIds = new LinkedHashSet<>();
        contentReviews.forEach(review -> contentIds.add(review.getContentId()));
        if (contentIds.isEmpty()) {
            return Map.of();
        }
        return byId(contentRepository.findAllById(contentIds), Content::getId);
    }

    private NotificationTargetPreviewDTO droppedPreview(
            NotificationTargetKey key,
            DroppedEntry droppedEntry,
            Map<ContentCoordinate, ContentCardDTO> cards) {
        if (droppedEntry == null) {
            return unavailable(key);
        }
        ContentCardDTO card = contentCard(droppedEntry.getContent(), cards);
        return new NotificationTargetPreviewDTO(key.targetType(), key.targetId(), "Review", droppedEntry.getComment(),
                null, null, card, status(card));
    }

    private NotificationTargetPreviewDTO pickPreview(NotificationTargetKey key, Pick pick) {
        if (pick == null || pick.getPicksTemplate() == null) {
            return unavailable(key);
        }
        PicksTemplate template = pick.getPicksTemplate();
        return new NotificationTargetPreviewDTO(key.targetType(), key.targetId(), template.getName(),
                template.getDescription(), null, null, null, ContentPreviewStatus.AVAILABLE);
    }

    private NotificationTargetPreviewDTO templatePreview(NotificationTargetKey key, PicksTemplate template) {
        if (template == null) {
            return unavailable(key);
        }
        return new NotificationTargetPreviewDTO(key.targetType(), key.targetId(), template.getName(),
                template.getDescription(), null, template.getCoverImage(), null, ContentPreviewStatus.AVAILABLE);
    }

    private ContentCardDTO contentCard(Content content, Map<ContentCoordinate, ContentCardDTO> cards) {
        if (content == null) {
            return null;
        }
        ContentCoordinate coordinate = ContentCoordinate.from(content);
        ContentCardDTO card = cards.get(coordinate);
        if (card != null) {
            return card;
        }
        return new ContentCardDTO(content.getId(), coordinate.type(), coordinate.tmdbId(), coordinate.seriesTmdbId(),
                coordinate.seasonNumber(), coordinate.episodeNumber(), null, null, null, null, null, null, null,
                null, null, null, null, null, ContentPreviewStatus.UNAVAILABLE);
    }

    private ContentPreviewStatus status(ContentCardDTO card) {
        if (card == null) {
            return ContentPreviewStatus.UNAVAILABLE;
        }
        return card.previewStatus() == null ? ContentPreviewStatus.AVAILABLE : card.previewStatus();
    }

    private NotificationTargetPreviewDTO unavailable(NotificationTargetKey key) {
        return new NotificationTargetPreviewDTO(key.targetType(), key.targetId(), null, null, null, null, null,
                ContentPreviewStatus.UNAVAILABLE);
    }

    private <T> Map<UUID, T> byId(Collection<T> entities, Function<T, UUID> idExtractor) {
        if (entities == null || entities.isEmpty()) {
            return Map.of();
        }
        Map<UUID, T> byId = new LinkedHashMap<>();
        for (T entity : entities) {
            if (entity != null) {
                UUID id = idExtractor.apply(entity);
                if (id != null) {
                    byId.putIfAbsent(id, entity);
                }
            }
        }
        return byId;
    }
}
