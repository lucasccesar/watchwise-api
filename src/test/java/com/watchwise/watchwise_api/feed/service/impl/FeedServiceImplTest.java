package com.watchwise.watchwise_api.feed.service.impl;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.service.CommentPreviewData;
import com.watchwise.watchwise_api.comment.service.impl.CommentPreviewAssembler;
import com.watchwise.watchwise_api.common.dto.CursorPageResponseDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAnswerDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameResultStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import com.watchwise.watchwise_api.dailygame.repository.UserDailyGameResultRepository;
import com.watchwise.watchwise_api.dailygame.service.impl.DailyChallengeResponseAssembler;
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
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;
import com.watchwise.watchwise_api.top5entry.mapper.Top5EntryMapper;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Arrays;
import java.util.HashMap;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedServiceImplTest {

    @Test
    @DisplayName("[FeedItemDTO] Should Expose Social Comment Fields - When Feed Item Is Built")
    void shouldExposeSocialCommentFieldsWhenFeedItemIsBuilt() {
        assertThat(Arrays.stream(FeedItemDTO.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .contains("commentsCount", "recentComments");
    }

    @Test
    @DisplayName("[PickPreviewDTO] Should Default RecentComments To Empty List - When Built Without Or With Null Comments")
    void shouldDefaultRecentCommentsToEmptyListWhenPickPreviewIsBuiltWithoutOrWithNullComments() {
        PickPreviewDTO withoutComments = new PickPreviewDTO(
                UUID.randomUUID(), null, PickVisibility.PUBLIC, LocalDateTime.now(), 0, 0, false, List.of());
        PickPreviewDTO withNullComments = new PickPreviewDTO(
                UUID.randomUUID(), null, PickVisibility.PUBLIC, LocalDateTime.now(), 0, 0, false, List.of(), null);

        assertThat(withoutComments.recentComments()).isNotNull().isEmpty();
        assertThat(withNullComments.recentComments()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("[PicksTemplatePreviewDTO] Should Default RecentComments To Empty List - When Built Without Or With Null Comments")
    void shouldDefaultRecentCommentsToEmptyListWhenTemplatePreviewIsBuiltWithoutOrWithNullComments() {
        PicksTemplatePreviewDTO shortForm = new PicksTemplatePreviewDTO(
                UUID.randomUUID(), PickOrigin.COMMUNITY, "Weekend Picks", null, null);
        PicksTemplatePreviewDTO withNullComments = new PicksTemplatePreviewDTO(
                UUID.randomUUID(), null, PickOrigin.COMMUNITY, "Weekend Picks", null, null, null, 0, 0, List.of(), 0,
                0, false, 0, null, null);

        assertThat(shortForm.recentComments()).isNotNull().isEmpty();
        assertThat(withNullComments.recentComments()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("[FeedItemDTO] Should Expose Current Top 5 Preview - When Feed Item Is Built")
    void shouldExposeCurrentTop5PreviewWhenFeedItemIsBuilt() {
        assertThat(Arrays.stream(FeedItemDTO.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .contains("top5");
    }

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
                pickPreview, templatePreview, null, createdAt);

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
                null, templatePreview, null, LocalDateTime.now());

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
        when(pickPreviewAssembler.assembleForFeed(eq(List.of(pick)), eq(viewerId)))
                .thenReturn(Map.of(pick.getId(), pickPreview));
        when(picksTemplatePreviewAssembler.assembleForFeed(any(), eq(viewerId)))
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
    @DisplayName("[getFeed] Should Merge DailyGameResult With Existing Sources - When A Result Is Shared")
    void shouldMergeDailyGameResultWithExistingSources() {
        stubFollowedIds();

        LocalDateTime sharedAt = LocalDateTime.of(2026, 9, 29, 12, 0);
        DailyChallenge challenge = buildChallenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult sharedResult = buildDailyGameResult(
                challenge, sharedAt, 4, 0, DailyGameResultStatus.FAILED);
        DiaryEntry diaryEntry = buildDiaryEntry(sharedAt.minusMinutes(1));

        stubEmptySources(21);
        when(diaryEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(diaryEntry));
        when(dailyGameResultRepository.findFeedCandidates(
                eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(sharedResult));
        when(likeService.getLikedDiaryEntryIds(eq(viewerId), eq(List.of(diaryEntry.getId())))).thenReturn(Set.of());

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, null);

        assertThat(result.content()).extracting(FeedItemDTO::eventType)
                .containsExactly(FeedEventType.DAILY_GAME_RESULT, FeedEventType.DIARY_ENTRY);
        FeedItemDTO item = result.content().getFirst();
        assertThat(item.id()).isEqualTo(sharedResult.getId());
        assertThat(item.dailyGameResult().challengeDate()).isEqualTo(challenge.getChallengeDate());
        assertThat(item.dailyGameResult().gameType()).isEqualTo(DailyGameType.MOVIE_BY_INFO);
        assertThat(item.dailyGameResult().targetKind()).isEqualTo(DailyGameTargetKind.MOVIE);
        assertThat(item.dailyGameResult().status()).isEqualTo(DailyGameViewStatus.FAILED);
        assertThat(item.dailyGameResult().attemptsUsed()).isEqualTo(4);
        assertThat(item.dailyGameResult().score()).isZero();
        assertThat(item.dailyGameResult().answer().title()).isEqualTo("Fight Club");
        assertThat(item.content()).isNull();
        assertThat(item.score()).isNull();
        assertThat(item.comment()).isNull();
        assertThat(item.likesCount()).isNull();
        assertThat(item.likedByMe()).isNull();
        assertThat(item.watchedWith()).isNull();
        assertThat(item.pick()).isNull();
        assertThat(item.picksTemplate()).isNull();
        assertThat(item.createdAt()).isEqualTo(sharedAt);
    }

    @Test
    @DisplayName("[getFeed] Should Report HasNext - When DailyGame Source Has More Candidates")
    void shouldReportHasNextWhenDailyGameSourceHasMoreCandidates() {
        stubFollowedIds();

        LocalDateTime now = LocalDateTime.of(2026, 9, 29, 12, 0);
        DailyChallenge challenge = buildChallenge(DailyGameType.MOVIE_BY_INFO, "550");
        UserDailyGameResult first = buildDailyGameResult(
                challenge, now, 1, 9, DailyGameResultStatus.COMPLETED);
        UserDailyGameResult beyondLimit = buildDailyGameResult(
                challenge, now.minusMinutes(1), 2, 8, DailyGameResultStatus.COMPLETED);

        stubEmptySources(2);
        when(dailyGameResultRepository.findFeedCandidates(
                eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 2))))
                .thenReturn(List.of(first, beyondLimit));

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, 1);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().id()).isEqualTo(first.getId());
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isNotNull();
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
        when(pickPreviewAssembler.assembleForFeed(eq(List.of(first)), eq(viewerId)))
                .thenReturn(Map.of(first.getId(), new PickPreviewDTO(first.getId(), null, PickVisibility.PUBLIC,
                        first.getCreatedAt(), 0, 0, false, List.of())));
        when(picksTemplatePreviewAssembler.assembleForFeed(any(), eq(viewerId))).thenReturn(Map.of());

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
    private Top5EntryMapper top5EntryMapper;

    @Mock
    private UserContentPosterService userContentPosterService;

    @Mock
    private UserDailyGameResultRepository dailyGameResultRepository;

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
    private DailyChallengeResponseAssembler dailyChallengeResponseAssembler;

    @Mock
    private PickPreviewAssembler pickPreviewAssembler;

    @Mock
    private PicksTemplatePreviewAssembler picksTemplatePreviewAssembler;

    @Mock
    private CommentPreviewAssembler commentPreviewAssembler;

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
        lenient().when(dailyChallengeResponseAssembler.toAnswer(any(DailyChallenge.class)))
                .thenReturn(new DailyGameAnswerDTO(
                        DailyGameTargetKind.MOVIE, "550", null, null, null, null, "Fight Club", "image.jpg"));
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
        assertThat(item.top5()).isNull();
    }

    @Test
    @DisplayName("[getFeed] Should Map DroppedEntry With Like Fields - When Building Feed Item")
    void shouldMapDroppedEntryWithLikeFieldsWhenBuildingFeedItem() {
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
        assertThat(item.likesCount()).isZero();
        assertThat(item.likedByMe()).isFalse();
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
    @DisplayName("[getFeed] Should Carry Current Top 5 Preview And Reuse It - When Multiple Events Share Author And Type")
    void shouldCarryCurrentTop5PreviewAndReuseItWhenMultipleEventsShareAuthorAndType() {
        stubFollowedIds();
        stubEmptyDiaryAndDropped(21);

        LocalDateTime now = LocalDateTime.now();
        Top5Entry newestEntry = buildTop5Entry(now);
        Top5Entry olderEntry = buildTop5Entry(now.minusMinutes(1));
        Top5EntryResponseDTO currentEntry = new Top5EntryResponseDTO(
                UUID.randomUUID(), ContentType.MOVIE, null, 1, now, now);
        UserContentPosterService.UserContentPosterKey posterKey =
                new UserContentPosterService.UserContentPosterKey(followedId, newestEntry.getContent().getId());
        String customPosterUrl = "https://image.tmdb.org/t/p/w342/current.png";

        when(top5EntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(newestEntry, olderEntry));
        when(top5EntryRepository.findCurrentPreviewsByUserIdsAndType(
                eq(List.of(followedId)), eq(ContentType.MOVIE)))
                .thenReturn(List.of(newestEntry));
        when(top5EntryMapper.top5EntryToResponseDto(newestEntry)).thenReturn(currentEntry);
        when(userContentPosterService.findByUserAndContentPairs(List.of(posterKey)))
                .thenReturn(Map.of(posterKey, customPosterUrl));
        List<Top5EntryResponseDTO> expectedTop5 = List.of(currentEntry.withCustomPosterUrl(customPosterUrl));

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, null);

        assertThat(result.content())
                .extracting(FeedItemDTO::top5)
                .containsExactly(expectedTop5, expectedTop5);
        verify(top5EntryRepository, times(1)).findCurrentPreviewsByUserIdsAndType(
                eq(List.of(followedId)), eq(ContentType.MOVIE));
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
        when(dailyGameResultRepository.findFeedCandidates(
                eq(List.of(followedId)), eq(cursorCreatedAt), eq(cursorId), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of());

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, cursor, null);

        assertThat(result.content()).isEmpty();
        verify(diaryEntryRepository).findFeedCandidates(
                eq(List.of(followedId)), eq(cursorCreatedAt), eq(cursorId), eq(PageRequest.of(0, 21)));
        verify(dailyGameResultRepository).findFeedCandidates(
                eq(List.of(followedId)), eq(cursorCreatedAt), eq(cursorId), eq(PageRequest.of(0, 21)));
    }

    @Test
    @DisplayName("[getFeed] Should Attach Own Comment Preview To DiaryEntry And DroppedEntry - When Both Have Comments")
    void shouldAttachOwnCommentPreviewToDiaryEntryAndDroppedEntryWhenBothHaveComments() {
        stubFollowedIds();
        LocalDateTime now = LocalDateTime.now();
        DiaryEntry diaryEntry = buildDiaryEntry(now);
        DroppedEntry droppedEntry = buildDroppedEntry(now.minusMinutes(1));
        CommentResponseDTO diaryComment = commentDto("diary comment");
        CommentResponseDTO droppedComment = commentDto("dropped comment");
        stubEmptyTop5PickAndTemplate(21);
        when(diaryEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(diaryEntry));
        when(droppedEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(droppedEntry));
        when(likeService.getLikedDiaryEntryIds(eq(viewerId), any())).thenReturn(Set.of());
        when(commentPreviewAssembler.assembleDiaryEntryPreviews(List.of(diaryEntry.getId()), viewerId))
                .thenReturn(Map.of(diaryEntry.getId(), new CommentPreviewData(5, List.of(diaryComment))));
        when(commentPreviewAssembler.assembleDroppedEntryPreviews(List.of(droppedEntry.getId()), viewerId))
                .thenReturn(Map.of(droppedEntry.getId(), new CommentPreviewData(2, List.of(droppedComment))));

        CursorPageResponseDTO<FeedItemDTO> result = feedService.getFeed(viewerId, null, null);

        FeedItemDTO diaryItem = result.content().get(0);
        assertThat(diaryItem.eventType()).isEqualTo(FeedEventType.DIARY_ENTRY);
        assertThat(diaryItem.commentsCount()).isEqualTo(5);
        assertThat(diaryItem.recentComments()).containsExactly(diaryComment);
        FeedItemDTO droppedItem = result.content().get(1);
        assertThat(droppedItem.eventType()).isEqualTo(FeedEventType.DROPPED);
        assertThat(droppedItem.commentsCount()).isEqualTo(2);
        assertThat(droppedItem.recentComments()).containsExactly(droppedComment);
    }

    @Test
    @DisplayName("[getFeed] Should Serialize Zero Count And Empty List - When DiaryEntry Has No Comments")
    void shouldSerializeZeroCountAndEmptyListWhenDiaryEntryHasNoComments() {
        stubFollowedIds();
        DiaryEntry diaryEntry = buildDiaryEntry(LocalDateTime.now());
        stubEmptyDroppedAndTop5(21);
        stubEmptyPickAndTemplate(21);
        when(diaryEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(diaryEntry));
        when(likeService.getLikedDiaryEntryIds(eq(viewerId), any())).thenReturn(Set.of());

        FeedItemDTO item = feedService.getFeed(viewerId, null, null).content().get(0);

        assertThat(item.commentsCount()).isZero();
        assertThat(item.recentComments()).isEmpty();
    }

    @Test
    @DisplayName("[getFeed] Should Keep Social Comment Fields Null - When Event Is Top5Update")
    void shouldKeepSocialCommentFieldsNullWhenEventIsTop5Update() {
        stubFollowedIds();
        Top5Entry top5Entry = buildTop5Entry(LocalDateTime.now());
        stubEmptyDiaryAndDropped(21);
        stubEmptyPickAndTemplate(21);
        when(top5EntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(top5Entry));

        FeedItemDTO item = feedService.getFeed(viewerId, null, null).content().get(0);

        assertThat(item.eventType()).isEqualTo(FeedEventType.TOP5_UPDATE);
        assertThat(item.commentsCount()).isNull();
        assertThat(item.recentComments()).isNull();
    }

    @Test
    @DisplayName("[getFeed] Should Request Comment Previews For Both Targets Of Pick Event - When Pick Event Is Built")
    void shouldRequestCommentPreviewsForBothTargetsOfPickEventWhenPickEventIsBuilt() {
        stubFollowedIds();
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = buildTemplate(now.minusMinutes(1));
        Pick pick = buildPick(template, now);
        stubEmptyDiaryAndDropped(21);
        when(top5EntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of());
        when(pickRepository.findFeedCandidates(eq(List.of(followedId)), eq(viewerId), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(pick));
        when(picksTemplateRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of());
        CommentResponseDTO pickComment = commentDto("pick comment");
        CommentResponseDTO templateComment = commentDto("template comment");
        PickPreviewDTO pickPreview = new PickPreviewDTO(pick.getId(), null, PickVisibility.PUBLIC, pick.getCreatedAt(),
                0, 1, false, List.of(), List.of(pickComment));
        PicksTemplatePreviewDTO templatePreview = new PicksTemplatePreviewDTO(template.getId(), null,
                PickOrigin.COMMUNITY, template.getName(), null, null, null, 0, 0, List.of(), 0, 1, false, 0, null,
                List.of(templateComment));
        when(pickPreviewAssembler.assembleForFeed(eq(List.of(pick)), eq(viewerId)))
                .thenReturn(Map.of(pick.getId(), pickPreview));
        when(picksTemplatePreviewAssembler.assembleForFeed(any(), eq(viewerId)))
                .thenReturn(Map.of(template.getId(), templatePreview));

        FeedItemDTO item = feedService.getFeed(viewerId, null, null).content().get(0);

        assertThat(item.pick().recentComments()).containsExactly(pickComment);
        assertThat(item.picksTemplate().recentComments()).containsExactly(templateComment);
        assertThat(item.commentsCount()).isNull();
        assertThat(item.recentComments()).isNull();
    }

    @Test
    @DisplayName("[getFeed] Should Attach Template Preview With Own Comments - When Event Is PicksTemplateCreated")
    void shouldAttachTemplatePreviewWithOwnCommentsWhenEventIsPicksTemplateCreated() {
        stubFollowedIds();
        PicksTemplate template = buildTemplate(LocalDateTime.now());
        stubEmptyDiaryAndDropped(21);
        when(top5EntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of());
        when(pickRepository.findFeedCandidates(eq(List.of(followedId)), eq(viewerId), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of());
        when(picksTemplateRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(template));
        CommentResponseDTO templateComment = commentDto("template comment");
        PicksTemplatePreviewDTO templatePreview = new PicksTemplatePreviewDTO(template.getId(), null,
                PickOrigin.COMMUNITY, template.getName(), null, null, null, 0, 0, List.of(), 0, 1, false, 0, null,
                List.of(templateComment));
        when(picksTemplatePreviewAssembler.assembleForFeed(any(), eq(viewerId)))
                .thenReturn(Map.of(template.getId(), templatePreview));

        FeedItemDTO item = feedService.getFeed(viewerId, null, null).content().get(0);

        assertThat(item.eventType()).isEqualTo(FeedEventType.PICKS_TEMPLATE_CREATED);
        assertThat(item.picksTemplate().recentComments()).containsExactly(templateComment);
        assertThat(item.pick()).isNull();
    }

    private void stubEmptyTop5PickAndTemplate(int expectedFetchLimit) {
        when(top5EntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        stubEmptyPickAndTemplate(expectedFetchLimit);
    }

    private void stubEmptyPickAndTemplate(int expectedFetchLimit) {
        when(pickRepository.findFeedCandidates(eq(List.of(followedId)), eq(viewerId), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        when(picksTemplateRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
    }

    private CommentResponseDTO commentDto(String text) {
        return new CommentResponseDTO(UUID.randomUUID(), null, null, null, null, null, null, null, null, text,
                false, LocalDateTime.now(), LocalDateTime.now(), 0, false);
    }

    private void stubFollowedIds() {
        when(followerRepository.findFollowedIdsByFollowerIdAndStatus(viewerId, FollowStatus.ACCEPTED))
                .thenReturn(List.of(followedId));
    }

    private void stubEmptySources(int expectedFetchLimit) {
        lenient().when(diaryEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        when(droppedEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        when(top5EntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        when(pickRepository.findFeedCandidates(eq(List.of(followedId)), eq(viewerId), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        when(picksTemplateRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        lenient().when(dailyGameResultRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
    }

    private void stubEmptyDroppedAndTop5(int expectedFetchLimit) {
        when(droppedEntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        when(top5EntryRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
                .thenReturn(List.of());
        when(dailyGameResultRepository.findFeedCandidates(eq(List.of(followedId)), isNull(), isNull(), eq(PageRequest.of(0, expectedFetchLimit))))
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

    private DailyChallenge buildChallenge(DailyGameType gameType, String targetTmdbId) {
        Map<String, Object> answerSnapshot = new HashMap<>();
        answerSnapshot.put("title", "Fight Club");
        return DailyChallenge.builder()
                .id(UUID.randomUUID())
                .challengeDate(LocalDate.of(2026, 9, 29))
                .gameType(gameType)
                .targetKind(gameType.targetKind())
                .targetTmdbId(targetTmdbId)
                .answerKey("movie:" + targetTmdbId)
                .imagePath("fight-club.jpg")
                .answerSnapshot(answerSnapshot)
                .displaySnapshot(Map.of())
                .createdAt(LocalDateTime.of(2026, 9, 28, 12, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 28, 12, 0))
                .build();
    }

    private UserDailyGameResult buildDailyGameResult(
            DailyChallenge challenge,
            LocalDateTime sharedAt,
            int attemptsUsed,
            int score,
            DailyGameResultStatus status) {
        UserDailyGameResult result = UserDailyGameResult.builder()
                .id(UUID.randomUUID())
                .user(followedUser)
                .dailyChallenge(challenge)
                .attemptsUsed(attemptsUsed)
                .score(score)
                .status(status)
                .completedAt(sharedAt)
                .createdAt(sharedAt.minusMinutes(5))
                .updatedAt(sharedAt)
                .build();
        result.setShareOnCompletion(true);
        result.markSharedAt(sharedAt);
        return result;
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
