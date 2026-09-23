package com.watchwise.watchwise_api.feed.service.impl;

import com.watchwise.watchwise_api.common.dto.CursorPageResponseDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.feed.dto.FeedEventType;
import com.watchwise.watchwise_api.feed.dto.FeedItemDTO;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.dto.PickPreviewDTO;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.service.impl.PickPreviewAssembler;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.pickstemplate.service.impl.PicksTemplatePreviewAssembler;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedServiceImplTest {

    @Test
    @DisplayName("[FeedItemDTO] Should Carry Pick And Template Previews - When A Pick Event Is Created")
    void shouldCarryPickAndTemplatePreviewsWhenAPickEventIsCreated() {
        UUID pickId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.now();
        PickPreviewDTO pickPreview = new PickPreviewDTO(
                pickId, null, PickVisibility.PUBLIC, createdAt, 2, 3, false, List.of());
        PicksTemplatePreviewDTO templatePreview = new PicksTemplatePreviewDTO(
                templateId, PickOrigin.COMMUNITY, "Weekend Picks", null, null);

        FeedItemDTO item = new FeedItemDTO(
                FeedEventType.PICK_CREATED, pickId, null, null, null, null, null, null, null, null,
                pickPreview, templatePreview, createdAt);

        assertThat(item.pick()).isEqualTo(pickPreview);
        assertThat(item.picksTemplate()).isEqualTo(templatePreview);
    }

    @Test
    @DisplayName("[FeedItemDTO] Should Carry Only Template Preview - When A Template Event Is Created")
    void shouldCarryOnlyTemplatePreviewWhenATemplateEventIsCreated() {
        UUID templateId = UUID.randomUUID();
        PicksTemplatePreviewDTO templatePreview = new PicksTemplatePreviewDTO(
                templateId, PickOrigin.COMMUNITY, "Weekend Picks", null, null);

        FeedItemDTO item = new FeedItemDTO(
                FeedEventType.PICKS_TEMPLATE_CREATED, templateId, null, null, null, null, null, null, null, null,
                null, templatePreview, LocalDateTime.now());

        assertThat(item.pick()).isNull();
        assertThat(item.picksTemplate()).isEqualTo(templatePreview);
    }

    @Test
    @DisplayName("[getFeed] Should Merge Five Sources And Map Pick Previews - When All Have Activity")
    void shouldMergeFiveSourcesAndMapPickPreviewsWhenAllHaveActivity() {
        stubFollowedIds();

        LocalDateTime now = LocalDateTime.now();
        DiaryEntry diaryEntry = buildDiaryEntry(now.minusMinutes(1));
        DroppedEntry droppedEntry = buildDroppedEntry(now.minusMinutes(2));
        Top5Entry top5Entry = buildTop5Entry(now.minusMinutes(3));
        PicksTemplate template = buildTemplate(now.minusMinutes(5));
        Pick pick = buildPick(template, now.minusMinutes(4));
        PickPreviewDTO pickPreview = new PickPreviewDTO(pick.getId(), null, PickVisibility.PUBLIC,
                pick.getCreatedAt(), 0, 0, false, List.of());
        PicksTemplatePreviewDTO templatePreview = new PicksTemplatePreviewDTO(template.getId(),
                PickOrigin.COMMUNITY, template.getName(), null, null);

        when(diaryEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(diaryEntry));
        when(droppedEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(droppedEntry));
        when(top5EntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(top5Entry));
        when(pickRepository.findFeedCandidates(eq(List.of(followedId)), eq(viewerId), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(pick));
        when(picksTemplateRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(template));
        when(pickPreviewAssembler.assemble(eq(List.of(pick)), eq(viewerId)))
                .thenReturn(Map.of(pick.getId(), pickPreview));
        when(picksTemplatePreviewAssembler.assemble(any(), eq(viewerId)))
                .thenReturn(Map.of(template.getId(), templatePreview));
        when(likeService.getLikedDiaryEntryIds(eq(viewerId), any())).thenReturn(Set.of());

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, null);

        assertThat(result.content()).extracting(FeedItemDTO::eventType)
                .containsExactly(
                        FeedEventType.DIARY_ENTRY,
                        FeedEventType.DROPPED,
                        FeedEventType.TOP5_UPDATE,
                        FeedEventType.PICK_CREATED,
                        FeedEventType.PICKS_TEMPLATE_CREATED);
        FeedItemDTO pickItem = result.content().stream()
                .filter(item -> item.eventType() == FeedEventType.PICK_CREATED).findFirst().orElseThrow();
        assertThat(pickItem.pick()).isEqualTo(pickPreview);
        assertThat(pickItem.picksTemplate()).isEqualTo(templatePreview);
        FeedItemDTO templateItem = result.content().stream()
                .filter(item -> item.eventType() == FeedEventType.PICKS_TEMPLATE_CREATED).findFirst().orElseThrow();
        assertThat(templateItem.pick()).isNull();
        assertThat(templateItem.picksTemplate()).isEqualTo(templatePreview);
    }

    @Test
    @DisplayName("[getFeed] Should Report HasNext - When Pick Source Has More Candidates")
    void shouldReportHasNextWhenPickSourceHasMoreCandidates() {
        stubFollowedIds();
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = buildTemplate(now);
        Pick first = buildPick(template, now);
        Pick beyondLimit = buildPick(template, now.minusMinutes(1));
        when(diaryEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 2))))
                .thenReturn(List.of());
        stubEmptyDroppedAndTop5(2);
        when(pickRepository.findFeedCandidates(eq(List.of(followedId)), eq(viewerId), isNull(), isNull(), eq(PageRequest.of(0, 2))))
                .thenReturn(List.of(first, beyondLimit));
        when(picksTemplateRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 2))))
                .thenReturn(List.of());
        when(pickPreviewAssembler.assemble(eq(List.of(first)), eq(viewerId)))
                .thenReturn(Map.of(first.getId(), new PickPreviewDTO(first.getId(), null, PickVisibility.PUBLIC,
                        first.getCreatedAt(), 0, 0, false, List.of())));
        when(picksTemplatePreviewAssembler.assemble(any(), eq(viewerId))).thenReturn(Map.of());

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, 1);

        assertThat(result.content()).hasSize(1);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isNotNull();
    }

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private DroppedEntryRepository droppedEntryRepository;

    @Mock
    private Top5EntryRepository top5EntryRepository;

    @Mock
    private PickRepository pickRepository;

    @Mock
    private PicksTemplateRepository picksTemplateRepository;

    @Mock
    private WatchCompanionRepository watchCompanionRepository;

    @Mock
    private LikeService likeService;

    @Mock
    private ContentMapper contentMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PickPreviewAssembler pickPreviewAssembler;

    @Mock
    private PicksTemplatePreviewAssembler picksTemplatePreviewAssembler;

    @InjectMocks
    private FeedServiceImpl feedService;

    private UUID viewerId;
    private UUID followedId;
    private User followedUser;

    @BeforeEach
    void setUp() {
        viewerId = UUID.randomUUID();
        followedId = UUID.randomUUID();
        followedUser = User.builder().id(followedId).username("marina").build();

        lenient().when(userMapper.userToUserPreviewDto(any(User.class)))
                .thenReturn(new UserPreviewDTO(followedId, "marina", "pic.png", true));
        lenient().when(contentMapper.contentToContentRefDto(any(Content.class)))
                .thenAnswer(invocation -> {
                    Content content = invocation.getArgument(0);
                    return new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(), null, null, null,
                            null, null, content.getCreatedAt(), content.getUpdatedAt());
                });
        lenient().when(watchCompanionRepository.findByDiaryEntryIdIn(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("[getFeed] Should Return Empty Content And HasNext False - When Viewer Follows Nobody")
    void shouldReturnEmptyContentAndHasNextFalseWhenViewerFollowsNobody() {
        when(followerRepository.findFollowedIdsByFollowerIdAndStatus(viewerId, FollowStatus.ACCEPTED)).thenReturn(List.of());

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, null);

        assertThat(result.content()).isEmpty();
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("[getFeed] Should Return Empty Content - When Followed Users Have No Activity")
    void shouldReturnEmptyContentWhenFollowedUsersHaveNoActivity() {
        stubFollowedIds();
        stubEmptySources(21);

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, null);

        assertThat(result.content()).isEmpty();
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("[getFeed] Should Merge Three Sources Ordered By CreatedAt Desc - When All Have Activity")
    void shouldMergeThreeSourcesOrderedByCreatedAtDescWhenAllHaveActivity() {
        stubFollowedIds();

        LocalDateTime now = LocalDateTime.now();
        DiaryEntry diaryEntry = buildDiaryEntry(now.minusMinutes(1));
        DroppedEntry droppedEntry = buildDroppedEntry(now.minusMinutes(2));
        Top5Entry top5Entry = buildTop5Entry(now);

        when(diaryEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(diaryEntry));
        when(droppedEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(droppedEntry));
        when(top5EntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(top5Entry));
        when(likeService.getLikedDiaryEntryIds(eq(viewerId), any())).thenReturn(Set.of());

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, null);

        assertThat(result.content()).extracting(FeedItemDTO::eventType)
                .containsExactly(FeedEventType.TOP5_UPDATE, FeedEventType.DIARY_ENTRY, FeedEventType.DROPPED);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("[getFeed] Should Map DiaryEntry Fields Including Likes - When Building Feed Item")
    void shouldMapDiaryEntryFieldsIncludingLikesWhenBuildingFeedItem() {
        stubFollowedIds();

        DiaryEntry diaryEntry = buildDiaryEntry(LocalDateTime.now());
        diaryEntry.setScore(8);
        diaryEntry.setComment("Great movie");
        diaryEntry.setLikesCount(3);

        when(diaryEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(diaryEntry));
        stubEmptyDroppedAndTop5(21);
        when(likeService.getLikedDiaryEntryIds(eq(viewerId), eq(List.of(diaryEntry.getId())))).thenReturn(Set.of(diaryEntry.getId()));

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, null);

        FeedItemDTO item = result.content().get(0);
        assertThat(item.eventType()).isEqualTo(FeedEventType.DIARY_ENTRY);
        assertThat(item.id()).isEqualTo(diaryEntry.getId());
        assertThat(item.score()).isEqualTo(8);
        assertThat(item.comment()).isEqualTo("Great movie");
        assertThat(item.likesCount()).isEqualTo(3);
        assertThat(item.likedByMe()).isTrue();
        assertThat(item.top5Type()).isNull();
    }

    @Test
    @DisplayName("[getFeed] Should Map DroppedEntry With Null Like Fields - When Building Feed Item")
    void shouldMapDroppedEntryWithNullLikeFieldsWhenBuildingFeedItem() {
        stubFollowedIds();
        stubEmptyDiaryAndTop5(21);

        DroppedEntry droppedEntry = buildDroppedEntry(LocalDateTime.now());
        droppedEntry.setComment("Couldn't finish it");

        when(droppedEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(droppedEntry));

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, null);

        FeedItemDTO item = result.content().get(0);
        assertThat(item.eventType()).isEqualTo(FeedEventType.DROPPED);
        assertThat(item.comment()).isEqualTo("Couldn't finish it");
        assertThat(item.score()).isNull();
        assertThat(item.likesCount()).isNull();
        assertThat(item.likedByMe()).isNull();
    }

    @Test
    @DisplayName("[getFeed] Should Map Top5Entry With Null Content And Type Set - When Building Feed Item")
    void shouldMapTop5EntryWithNullContentAndTypeSetWhenBuildingFeedItem() {
        stubFollowedIds();
        stubEmptyDiaryAndDropped(21);

        Top5Entry top5Entry = buildTop5Entry(LocalDateTime.now());

        when(top5EntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(top5Entry));

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, null);

        FeedItemDTO item = result.content().get(0);
        assertThat(item.eventType()).isEqualTo(FeedEventType.TOP5_UPDATE);
        assertThat(item.top5Type()).isEqualTo(ContentType.MOVIE);
        assertThat(item.content()).isNull();
        assertThat(item.comment()).isNull();
        assertThat(item.score()).isNull();
    }

    @Test
    @DisplayName("[getFeed] Should Use Default Size - When Size Is Null")
    void shouldUseDefaultSizeWhenSizeIsNull() {
        stubFollowedIds();
        stubEmptySources(21);

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, null);

        assertThat(result.size()).isEqualTo(20);
        verify(diaryEntryRepository).findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21)));
    }

    @Test
    @DisplayName("[getFeed] Should Clamp To Max Size - When Size Exceeds Max")
    void shouldClampToMaxSizeWhenSizeExceedsMax() {
        stubFollowedIds();
        stubEmptySources(51);

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, 500);

        assertThat(result.size()).isEqualTo(50);
        verify(diaryEntryRepository).findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 51)));
    }

    @Test
    @DisplayName("[getFeed] Should Throw BadRequestException - When Size Is Zero")
    void shouldThrowBadRequestExceptionWhenSizeIsZero() {
        assertThatThrownBy(() -> feedService.getFeed(viewerId, null, 0))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("size must be greater than 0");
    }

    @Test
    @DisplayName("[getFeed] Should Throw BadRequestException - When Size Is Negative")
    void shouldThrowBadRequestExceptionWhenSizeIsNegative() {
        assertThatThrownBy(() -> feedService.getFeed(viewerId, null, -1))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("size must be greater than 0");
    }

    @Test
    @DisplayName("[getFeed] Should Throw BadRequestException - When Cursor Is Malformed")
    void shouldThrowBadRequestExceptionWhenCursorIsMalformed() {
        assertThatThrownBy(() -> feedService.getFeed(viewerId, "not-a-valid-cursor!!", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid cursor");
    }

    @Test
    @DisplayName("[getFeed] Should Trim Page And Return HasNext True - When Merged Candidates Exceed Size")
    void shouldTrimPageAndReturnHasNextTrueWhenMergedCandidatesExceedSize() {
        stubFollowedIds();

        LocalDateTime now = LocalDateTime.now();
        DiaryEntry newer = buildDiaryEntry(now);
        DiaryEntry older = buildDiaryEntry(now.minusMinutes(1));

        when(diaryEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 2))))
                .thenReturn(List.of(newer, older));
        stubEmptyDroppedAndTop5(2);
        when(likeService.getLikedDiaryEntryIds(eq(viewerId), any())).thenReturn(Set.of());

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, 1);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).id()).isEqualTo(newer.getId());
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isNotNull();
    }

    @Test
    @DisplayName("[getFeed] Should Return HasNext True - When A Single Source Has More Beyond An Unfull Page")
    void shouldReturnHasNextTrueWhenASingleSourceHasMoreBeyondAnUnfullPage() {
        stubFollowedIds();

        LocalDateTime now = LocalDateTime.now();
        DiaryEntry onlyReturned = buildDiaryEntry(now);
        DiaryEntry beyondLimit = buildDiaryEntry(now.minusMinutes(1));

        when(diaryEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 2))))
                .thenReturn(List.of(onlyReturned, beyondLimit));
        stubEmptyDroppedAndTop5(2);
        when(likeService.getLikedDiaryEntryIds(eq(viewerId), any())).thenReturn(Set.of());

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, 1);

        assertThat(result.content()).hasSize(1);
        assertThat(result.hasNext()).isTrue();
    }

    @Test
    @DisplayName("[getFeed] Should Decode Cursor And Pass CreatedAt And Id To Repositories - When Cursor Is Provided")
    void shouldDecodeCursorAndPassCreatedAtAndIdToRepositoriesWhenCursorIsProvided() {
        stubFollowedIds();

        LocalDateTime cursorCreatedAt = LocalDateTime.of(2026, 8, 20, 10, 0, 0);
        UUID cursorId = UUID.randomUUID();
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((cursorCreatedAt + "|" + cursorId).getBytes());

        when(diaryEntryRepository.findFeedCandidates(
                eq(List.of(followedId)), eq(cursorCreatedAt), eq(cursorId), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of());
        when(droppedEntryRepository.findFeedCandidates(
                eq(List.of(followedId)), eq(cursorCreatedAt), eq(cursorId), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of());
        when(top5EntryRepository.findFeedCandidates(
                eq(List.of(followedId)), eq(cursorCreatedAt), eq(cursorId), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of());

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, cursor, null);

        assertThat(result.content()).isEmpty();
        verify(diaryEntryRepository).findFeedCandidates(
                eq(List.of(followedId)), eq(cursorCreatedAt), eq(cursorId), eq(PageRequest.of(0, 21)));
    }

    private void stubFollowedIds() {
        when(followerRepository.findFollowedIdsByFollowerIdAndStatus(viewerId, FollowStatus.ACCEPTED))
                .thenReturn(List.of(followedId));
    }

    private void stubEmptySources(int expectedFetchLimit) {
        when(diaryEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        when(droppedEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        when(top5EntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        when(pickRepository.findFeedCandidates(eq(List.of(followedId)), eq(viewerId), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        when(picksTemplateRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
    }

    private void stubEmptyDroppedAndTop5(int expectedFetchLimit) {
        when(droppedEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        when(top5EntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
    }

    private void stubEmptyDiaryAndTop5(int expectedFetchLimit) {
        when(diaryEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        when(top5EntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        lenient().when(likeService.getLikedDiaryEntryIds(eq(viewerId), any())).thenReturn(Set.of());
    }

    private void stubEmptyDiaryAndDropped(int expectedFetchLimit) {
        when(diaryEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        when(droppedEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        lenient().when(likeService.getLikedDiaryEntryIds(eq(viewerId), any())).thenReturn(Set.of());
    }

    private DiaryEntry buildDiaryEntry(LocalDateTime createdAt) {
        Content content = Content.builder().id(UUID.randomUUID()).type(ContentType.MOVIE).tmdbId("100")
                .createdAt(createdAt).updatedAt(createdAt).build();
        return DiaryEntry.builder()
                .id(UUID.randomUUID())
                .user(followedUser)
                .content(content)
                .watchNumber(1)
                .autoGenerated(false)
                .likesCount(0)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private DroppedEntry buildDroppedEntry(LocalDateTime createdAt) {
        Content content = Content.builder().id(UUID.randomUUID()).type(ContentType.SERIES).tmdbId("200")
                .createdAt(createdAt).updatedAt(createdAt).build();
        return DroppedEntry.builder()
                .id(UUID.randomUUID())
                .user(followedUser)
                .content(content)
                .type(ContentType.SERIES)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private Top5Entry buildTop5Entry(LocalDateTime createdAt) {
        Content content = Content.builder().id(UUID.randomUUID()).type(ContentType.MOVIE).tmdbId("300")
                .createdAt(createdAt).updatedAt(createdAt).build();
        return Top5Entry.builder()
                .id(UUID.randomUUID())
                .user(followedUser)
                .content(content)
                .type(ContentType.MOVIE)
                .position(1)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private PicksTemplate buildTemplate(LocalDateTime createdAt) {
        return PicksTemplate.builder()
                .id(UUID.randomUUID())
                .creator(followedUser)
                .origin(PickOrigin.COMMUNITY)
                .name("Feed template")
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private Pick buildPick(PicksTemplate template, LocalDateTime createdAt) {
        return Pick.builder()
                .id(UUID.randomUUID())
                .user(followedUser)
                .picksTemplate(template)
                .visibility(PickVisibility.PUBLIC)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .likesCount(0)
                .build();
    }
}
