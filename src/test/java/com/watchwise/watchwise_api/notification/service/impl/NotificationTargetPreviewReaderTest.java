package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diarycompletion.entity.LegacyDiaryMigrationMap;
import com.watchwise.watchwise_api.diarycompletion.repository.LegacyDiaryMigrationMapRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.notification.dto.NotificationTargetPreviewDTO;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationTargetPreviewReaderTest {

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private ContentReviewRepository contentReviewRepository;

    @Mock
    private LegacyDiaryMigrationMapRepository migrationMapRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private DroppedEntryRepository droppedEntryRepository;

    @Mock
    private UserListRepository userListRepository;

    @Mock
    private PickRepository pickRepository;

    @Mock
    private PicksTemplateRepository picksTemplateRepository;

    @Mock
    private ContentCardAssembler contentCardAssembler;

    private NotificationTargetPreviewReader reader;
    private ContentCardContext context;

    @BeforeEach
    void setUp() {
        reader = new NotificationTargetPreviewReader(
                commentRepository,
                diaryEntryRepository,
                contentReviewRepository,
                migrationMapRepository,
                contentRepository,
                droppedEntryRepository,
                userListRepository,
                pickRepository,
                picksTemplateRepository,
                contentCardAssembler);
        context = new ContentCardContext("en-US", "US", null, null);
    }

    @Test
    void shouldResolveTypedContentReviewsAndMappedLegacyDiaryLinksWithoutGuessingByUuid() {
        UUID legacyDiaryId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        Content content = content(UUID.randomUUID(), "series-42");
        ContentReview review = ContentReview.builder()
                .id(reviewId)
                .contentId(content.getId())
                .comment("Mapped review text")
                .scoreAtPublication(8)
                .build();
        LegacyDiaryMigrationMap mapping = LegacyDiaryMigrationMap.builder()
                .oldDiaryEntryId(legacyDiaryId)
                .destinationKind("CONTENT_REVIEW")
                .destinationId(reviewId)
                .build();
        ContentCardDTO card = card(content, ContentPreviewStatus.AVAILABLE);
        when(diaryEntryRepository.findByIdInWithContentAndUser(anyCollection())).thenReturn(List.of());
        when(migrationMapRepository.findByOldDiaryEntryIdIn(anyCollection())).thenReturn(List.of(mapping));
        when(contentReviewRepository.findAllById(any())).thenReturn(List.of(review));
        when(contentRepository.findAllById(any())).thenReturn(List.of(content));
        when(contentCardAssembler.assemble(anyCollection(), eq(context), eq(ContentCardFieldSet.BASIC_METADATA)))
                .thenReturn(Map.of(ContentCoordinate.from(content), card));

        Map<NotificationTargetKey, NotificationTargetPreviewDTO> result = reader.read(List.of(
                key(NotificationTargetType.DIARY_ENTRY, legacyDiaryId),
                key(NotificationTargetType.CONTENT_REVIEW, reviewId)), context);

        assertThat(result.get(key(NotificationTargetType.DIARY_ENTRY, legacyDiaryId)))
                .extracting(NotificationTargetPreviewDTO::targetType, NotificationTargetPreviewDTO::targetId,
                        NotificationTargetPreviewDTO::excerpt, NotificationTargetPreviewDTO::score)
                .containsExactly(NotificationTargetType.DIARY_ENTRY, legacyDiaryId, "Mapped review text", 8);
        assertThat(result.get(key(NotificationTargetType.CONTENT_REVIEW, reviewId)))
                .extracting(NotificationTargetPreviewDTO::targetType, NotificationTargetPreviewDTO::targetId,
                        NotificationTargetPreviewDTO::excerpt, NotificationTargetPreviewDTO::score)
                .containsExactly(NotificationTargetType.CONTENT_REVIEW, reviewId, "Mapped review text", 8);
    }

    @Test
    void shouldReadEveryTargetTypeWithItsCompactPreview() {
        UUID commentId = UUID.randomUUID();
        UUID diaryId = UUID.randomUUID();
        UUID droppedId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        UUID pickId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        Content content = content(UUID.randomUUID(), "550");
        Comment comment = Comment.builder().id(commentId).text("Comment body").build();
        DiaryEntry diary = DiaryEntry.builder().id(diaryId).content(content).comment("Diary review")
                .score(9).build();
        DroppedEntry dropped = DroppedEntry.builder().id(droppedId).content(content).comment("Dropped review")
                .type(ContentType.MOVIE).build();
        UserList list = UserList.builder().id(listId).name("My list").description("List description")
                .visibility(UserListVisibility.PUBLIC).build();
        PicksTemplate template = template(templateId, "Template name", "Template description", "cover.jpg");
        Pick pick = Pick.builder().id(pickId).picksTemplate(template).visibility(PickVisibility.PUBLIC).build();
        ContentCardDTO card = card(content, ContentPreviewStatus.AVAILABLE);

        when(commentRepository.findByIdInWithUser(anyCollection())).thenReturn(List.of(comment));
        when(diaryEntryRepository.findByIdInWithContentAndUser(anyCollection())).thenReturn(List.of(diary));
        when(droppedEntryRepository.findByIdInWithUserAndContent(anyCollection())).thenReturn(List.of(dropped));
        when(userListRepository.findByIdInWithUser(anyCollection())).thenReturn(List.of(list));
        when(pickRepository.findByIdInWithPicksTemplate(anyCollection())).thenReturn(List.of(pick));
        when(picksTemplateRepository.findByIdInWithCreator(anyCollection())).thenReturn(List.of(template));
        when(contentCardAssembler.assemble(anyCollection(), eq(context), eq(ContentCardFieldSet.BASIC_METADATA)))
                .thenReturn(Map.of(ContentCoordinate.from(content), card));

        Map<NotificationTargetKey, NotificationTargetPreviewDTO> result = reader.read(List.of(
                key(NotificationTargetType.COMMENT, commentId),
                key(NotificationTargetType.DIARY_ENTRY, diaryId),
                key(NotificationTargetType.DROPPED_ENTRY, droppedId),
                key(NotificationTargetType.USER_LIST, listId),
                key(NotificationTargetType.PICK, pickId),
                key(NotificationTargetType.PICKS_TEMPLATE, templateId)), context);

        assertThat(result.get(key(NotificationTargetType.COMMENT, commentId)))
                .extracting(NotificationTargetPreviewDTO::title, NotificationTargetPreviewDTO::excerpt,
                        NotificationTargetPreviewDTO::contentCard, NotificationTargetPreviewDTO::previewStatus)
                .containsExactly("Comment", "Comment body", null, ContentPreviewStatus.AVAILABLE);
        assertThat(result.get(key(NotificationTargetType.DIARY_ENTRY, diaryId)))
                .extracting(NotificationTargetPreviewDTO::excerpt, NotificationTargetPreviewDTO::score,
                        NotificationTargetPreviewDTO::contentCard, NotificationTargetPreviewDTO::previewStatus)
                .containsExactly("Diary review", 9, card, ContentPreviewStatus.AVAILABLE);
        assertThat(result.get(key(NotificationTargetType.DROPPED_ENTRY, droppedId)))
                .extracting(NotificationTargetPreviewDTO::excerpt, NotificationTargetPreviewDTO::score,
                        NotificationTargetPreviewDTO::contentCard, NotificationTargetPreviewDTO::previewStatus)
                .containsExactly("Dropped review", null, card, ContentPreviewStatus.AVAILABLE);
        assertThat(result.get(key(NotificationTargetType.USER_LIST, listId)))
                .extracting(NotificationTargetPreviewDTO::title, NotificationTargetPreviewDTO::excerpt,
                        NotificationTargetPreviewDTO::contentCard, NotificationTargetPreviewDTO::previewStatus)
                .containsExactly("My list", "List description", null, ContentPreviewStatus.AVAILABLE);
        assertThat(result.get(key(NotificationTargetType.PICK, pickId)))
                .extracting(NotificationTargetPreviewDTO::title, NotificationTargetPreviewDTO::excerpt,
                        NotificationTargetPreviewDTO::contentCard, NotificationTargetPreviewDTO::previewStatus)
                .containsExactly("Template name", "Template description", null, ContentPreviewStatus.AVAILABLE);
        assertThat(result.get(key(NotificationTargetType.PICKS_TEMPLATE, templateId)))
                .extracting(NotificationTargetPreviewDTO::title, NotificationTargetPreviewDTO::excerpt,
                        NotificationTargetPreviewDTO::coverImageUrl, NotificationTargetPreviewDTO::previewStatus)
                .containsExactly("Template name", "Template description", "cover.jpg", ContentPreviewStatus.AVAILABLE);

        verify(commentRepository).findByIdInWithUser(Set.of(commentId));
        verify(diaryEntryRepository).findByIdInWithContentAndUser(Set.of(diaryId));
        verify(droppedEntryRepository).findByIdInWithUserAndContent(Set.of(droppedId));
        verify(userListRepository).findByIdInWithUser(Set.of(listId));
        verify(pickRepository).findByIdInWithPicksTemplate(Set.of(pickId));
        verify(picksTemplateRepository).findByIdInWithCreator(Set.of(templateId));
    }

    @Test
    void shouldDeduplicateKeysAndContentCoordinatesAcrossEachBatch() {
        UUID diaryId = UUID.randomUUID();
        UUID droppedId = UUID.randomUUID();
        Content content = content(UUID.randomUUID(), "550");
        DiaryEntry diary = DiaryEntry.builder().id(diaryId).content(content).comment("Diary review").build();
        DroppedEntry dropped = DroppedEntry.builder().id(droppedId).content(content).type(ContentType.MOVIE).build();
        ContentCardDTO card = card(content, ContentPreviewStatus.PARTIAL);
        when(diaryEntryRepository.findByIdInWithContentAndUser(anyCollection())).thenReturn(List.of(diary));
        when(droppedEntryRepository.findByIdInWithUserAndContent(anyCollection())).thenReturn(List.of(dropped));
        when(contentCardAssembler.assemble(anyCollection(), eq(context), eq(ContentCardFieldSet.BASIC_METADATA)))
                .thenReturn(Map.of(ContentCoordinate.from(content), card));

        Map<NotificationTargetKey, NotificationTargetPreviewDTO> result = reader.read(List.of(
                key(NotificationTargetType.DIARY_ENTRY, diaryId),
                key(NotificationTargetType.DIARY_ENTRY, diaryId),
                key(NotificationTargetType.DROPPED_ENTRY, droppedId),
                key(NotificationTargetType.DROPPED_ENTRY, droppedId)), context);

        assertThat(result).hasSize(2);
        assertThat(result.get(key(NotificationTargetType.DIARY_ENTRY, diaryId)).previewStatus())
                .isEqualTo(ContentPreviewStatus.PARTIAL);
        assertThat(result.get(key(NotificationTargetType.DROPPED_ENTRY, droppedId)).previewStatus())
                .isEqualTo(ContentPreviewStatus.PARTIAL);
        verify(diaryEntryRepository, times(1)).findByIdInWithContentAndUser(Set.of(diaryId));
        verify(droppedEntryRepository, times(1)).findByIdInWithUserAndContent(Set.of(droppedId));

        ArgumentCaptor<Collection<ContentCardSpec>> specs = ArgumentCaptor.forClass(Collection.class);
        verify(contentCardAssembler).assemble(specs.capture(), eq(context), eq(ContentCardFieldSet.BASIC_METADATA));
        assertThat(specs.getValue()).hasSize(1);
    }

    @Test
    void shouldKeepMissingTargetsAsUnavailablePreviews() {
        UUID commentId = UUID.randomUUID();
        UUID diaryId = UUID.randomUUID();
        UUID droppedId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        UUID pickId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        when(commentRepository.findByIdInWithUser(anyCollection())).thenReturn(List.of());
        when(diaryEntryRepository.findByIdInWithContentAndUser(anyCollection())).thenReturn(List.of());
        when(droppedEntryRepository.findByIdInWithUserAndContent(anyCollection())).thenReturn(List.of());
        when(userListRepository.findByIdInWithUser(anyCollection())).thenReturn(List.of());
        when(pickRepository.findByIdInWithPicksTemplate(anyCollection())).thenReturn(List.of());
        when(picksTemplateRepository.findByIdInWithCreator(anyCollection())).thenReturn(List.of());

        List<NotificationTargetKey> keys = List.of(
                key(NotificationTargetType.COMMENT, commentId),
                key(NotificationTargetType.DIARY_ENTRY, diaryId),
                key(NotificationTargetType.DROPPED_ENTRY, droppedId),
                key(NotificationTargetType.USER_LIST, listId),
                key(NotificationTargetType.PICK, pickId),
                key(NotificationTargetType.PICKS_TEMPLATE, templateId));

        Map<NotificationTargetKey, NotificationTargetPreviewDTO> result = reader.read(keys, context);

        assertThat(result).hasSize(6);
        for (NotificationTargetKey key : keys) {
            assertThat(result.get(key).targetType()).isEqualTo(key.targetType());
            assertThat(result.get(key).targetId()).isEqualTo(key.targetId());
            assertThat(result.get(key).previewStatus()).isEqualTo(ContentPreviewStatus.UNAVAILABLE);
        }
    }

    private NotificationTargetKey key(NotificationTargetType type, UUID id) {
        return new NotificationTargetKey(type, id);
    }

    private Content content(UUID id, String tmdbId) {
        return Content.builder().id(id).type(ContentType.MOVIE).tmdbId(tmdbId)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private PicksTemplate template(UUID id, String name, String description, String coverImage) {
        return PicksTemplate.builder().id(id).origin(PickOrigin.COMMUNITY).name(name).description(description)
                .coverImage(coverImage).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private ContentCardDTO card(Content content, ContentPreviewStatus status) {
        return new ContentCardDTO(content.getId(), content.getType(), content.getTmdbId(), null, null, null,
                "Fight Club", null, null, LocalDate.of(1999, 10, 15), 1999, 139, null, null, null, null, null,
                null, status);
    }
}
