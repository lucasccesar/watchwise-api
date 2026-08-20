package com.watchwise.watchwise_api.comment.service.impl;

import com.watchwise.watchwise_api.comment.dto.CommentCreationDTO;
import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.mapper.CommentMapper;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private UserListRepository userListRepository;

    @Mock
    private UserListItemRepository userListItemRepository;

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private CommentMapper commentMapper;

    @InjectMocks
    private CommentServiceImpl commentService;

    @Captor
    private ArgumentCaptor<Comment> commentCaptor;

    @Captor
    private ArgumentCaptor<PageRequest> pageRequestCaptor;

    private UUID lucasId;
    private UUID marinaId;
    private User lucas;
    private User marina;
    private UUID contentId;
    private Content fightClub;
    private UUID listId;
    private UserList scifi;
    private UUID diaryEntryId;
    private DiaryEntry diaryEntry;
    private CommentResponseDTO responseDto;

    @BeforeEach
    void setUp() {
        lucasId = UUID.randomUUID();
        marinaId = UUID.randomUUID();

        lucas = User.builder()
                .id(lucasId)
                .username("lucas")
                .email("lucas@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        marina = User.builder()
                .id(marinaId)
                .username("marina")
                .email("marina@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        contentId = UUID.randomUUID();
        fightClub = Content.builder()
                .id(contentId)
                .tmdbId("550")
                .type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        listId = UUID.randomUUID();
        scifi = UserList.builder()
                .id(listId)
                .user(marina)
                .name("Best sci-fi of the 90s")
                .visibility(UserListVisibility.PUBLIC)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        diaryEntryId = UUID.randomUUID();
        diaryEntry = DiaryEntry.builder()
                .id(diaryEntryId)
                .user(marina)
                .content(fightClub)
                .watchNumber(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        responseDto = new CommentResponseDTO(
                UUID.randomUUID(), null, contentId, null, null, null, "Great movie!", false,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private Comment buildContentComment(Content content) {
        return Comment.builder()
                .id(UUID.randomUUID())
                .user(lucas)
                .content(content)
                .text("Existing comment")
                .containsSpoiler(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Comment buildListComment(UserList list) {
        return Comment.builder()
                .id(UUID.randomUUID())
                .user(lucas)
                .list(list)
                .text("Existing comment")
                .containsSpoiler(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Comment buildDiaryEntryComment(DiaryEntry entry) {
        return Comment.builder()
                .id(UUID.randomUUID())
                .user(lucas)
                .diaryEntry(entry)
                .text("Existing comment")
                .containsSpoiler(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ---------- getCommentsForContent ----------

    @Test
    @DisplayName("[getCommentsForContent] Should Return Page Of CommentResponseDTO - When Content Exists")
    void shouldReturnPageOfCommentResponseDtoWhenContentExists() {
        Comment comment = buildContentComment(fightClub);
        when(contentRepository.existsById(contentId)).thenReturn(true);
        when(commentRepository.findByContentIdOrderByCreatedAtAsc(eq(contentId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(comment)));
        when(commentMapper.commentToResponseDto(comment)).thenReturn(responseDto);

        Page<CommentResponseDTO> result = commentService.getCommentsForContent(contentId, 1, 10);

        assertThat(result.getContent()).containsExactly(responseDto);
    }

    @Test
    @DisplayName("[getCommentsForContent] Should Return Empty Page - When Content Has No Comments")
    void shouldReturnEmptyPageWhenContentHasNoComments() {
        when(contentRepository.existsById(contentId)).thenReturn(true);
        when(commentRepository.findByContentIdOrderByCreatedAtAsc(eq(contentId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<CommentResponseDTO> result = commentService.getCommentsForContent(contentId, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verify(commentMapper, never()).commentToResponseDto(any());
    }

    @Test
    @DisplayName("[getCommentsForContent] Should Throw NotFoundException - When Content Does Not Exist")
    void shouldThrowNotFoundExceptionWhenContentDoesNotExistForListing() {
        when(contentRepository.existsById(contentId)).thenReturn(false);

        assertThatThrownBy(() -> commentService.getCommentsForContent(contentId, 1, 10))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Content not found");

        verifyNoInteractions(commentRepository);
    }

    @Test
    @DisplayName("[getCommentsForContent] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNull() {
        stubContentCommentsEmptyPage();

        commentService.getCommentsForContent(contentId, null, 10);

        verify(commentRepository).findByContentIdOrderByCreatedAtAsc(eq(contentId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(CommentServiceImpl.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getCommentsForContent] Should Use Default Page - When Page Number Is Zero")
    void shouldUseDefaultPageWhenPageNumberIsZero() {
        stubContentCommentsEmptyPage();

        commentService.getCommentsForContent(contentId, 0, 10);

        verify(commentRepository).findByContentIdOrderByCreatedAtAsc(eq(contentId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(CommentServiceImpl.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getCommentsForContent] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositive() {
        stubContentCommentsEmptyPage();

        commentService.getCommentsForContent(contentId, 3, 10);

        verify(commentRepository).findByContentIdOrderByCreatedAtAsc(eq(contentId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[getCommentsForContent] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegative() {
        when(contentRepository.existsById(contentId)).thenReturn(true);

        assertThatThrownBy(() -> commentService.getCommentsForContent(contentId, -1, 10))
                .isInstanceOf(BadRequestException.class);

        verify(commentRepository, never()).findByContentIdOrderByCreatedAtAsc(any(), any());
    }

    @Test
    @DisplayName("[getCommentsForContent] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNull() {
        stubContentCommentsEmptyPage();

        commentService.getCommentsForContent(contentId, 1, null);

        verify(commentRepository).findByContentIdOrderByCreatedAtAsc(eq(contentId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(CommentServiceImpl.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getCommentsForContent] Should Use Default Page Size - When Page Size Exceeds Limit")
    void shouldUseDefaultPageSizeWhenPageSizeExceedsLimit() {
        stubContentCommentsEmptyPage();

        commentService.getCommentsForContent(contentId, 1, 1001);

        verify(commentRepository).findByContentIdOrderByCreatedAtAsc(eq(contentId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(CommentServiceImpl.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getCommentsForContent] Should Use Provided Page Size - When Page Size Is Valid")
    void shouldUseProvidedPageSizeWhenPageSizeIsValid() {
        stubContentCommentsEmptyPage();

        commentService.getCommentsForContent(contentId, 1, 25);

        verify(commentRepository).findByContentIdOrderByCreatedAtAsc(eq(contentId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("[getCommentsForContent] Should Use Provided Page Size - When Page Size Is At Max Limit")
    void shouldUseProvidedPageSizeWhenPageSizeIsAtMaxLimit() {
        stubContentCommentsEmptyPage();

        commentService.getCommentsForContent(contentId, 1, 1000);

        verify(commentRepository).findByContentIdOrderByCreatedAtAsc(eq(contentId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(1000);
    }

    @Test
    @DisplayName("[getCommentsForContent] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegative() {
        when(contentRepository.existsById(contentId)).thenReturn(true);

        assertThatThrownBy(() -> commentService.getCommentsForContent(contentId, 1, -5))
                .isInstanceOf(BadRequestException.class);

        verify(commentRepository, never()).findByContentIdOrderByCreatedAtAsc(any(), any());
    }

    @Test
    @DisplayName("[getCommentsForContent] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZero() {
        when(contentRepository.existsById(contentId)).thenReturn(true);

        assertThatThrownBy(() -> commentService.getCommentsForContent(contentId, 1, 0))
                .isInstanceOf(BadRequestException.class);

        verify(commentRepository, never()).findByContentIdOrderByCreatedAtAsc(any(), any());
    }

    private void stubContentCommentsEmptyPage() {
        when(contentRepository.existsById(contentId)).thenReturn(true);
        when(commentRepository.findByContentIdOrderByCreatedAtAsc(any(), any(PageRequest.class))).thenReturn(Page.empty());
    }

    // ---------- getCommentsForList ----------

    @Test
    @DisplayName("[getCommentsForList] Should Return Page - When List Is Public")
    void shouldReturnPageWhenListIsPublic() {
        Comment comment = buildListComment(scifi);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(commentRepository.findByListIdOrderByCreatedAtAsc(eq(listId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(comment)));
        when(commentMapper.commentToResponseDto(comment)).thenReturn(responseDto);

        Page<CommentResponseDTO> result = commentService.getCommentsForList(lucasId, listId, 1, 10);

        assertThat(result.getContent()).containsExactly(responseDto);
    }

    @Test
    @DisplayName("[getCommentsForList] Should Return Empty Page - When List Has No Comments")
    void shouldReturnEmptyPageWhenListHasNoComments() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(commentRepository.findByListIdOrderByCreatedAtAsc(eq(listId), any(PageRequest.class))).thenReturn(Page.empty());

        Page<CommentResponseDTO> result = commentService.getCommentsForList(lucasId, listId, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verify(commentMapper, never()).commentToResponseDto(any());
    }

    @Test
    @DisplayName("[getCommentsForList] Should Throw NotFoundException - When List Does Not Exist")
    void shouldThrowNotFoundExceptionWhenListDoesNotExistForListing() {
        when(userListRepository.findById(listId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.getCommentsForList(lucasId, listId, 1, 10))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verifyNoInteractions(commentRepository);
    }

    @Test
    @DisplayName("[getCommentsForList] Should Return Page - When List Is Private And Viewer Is The Owner")
    void shouldReturnPageWhenListIsPrivateAndViewerIsTheOwner() {
        scifi.setVisibility(UserListVisibility.PRIVATE);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(commentRepository.findByListIdOrderByCreatedAtAsc(eq(listId), any(PageRequest.class))).thenReturn(Page.empty());

        assertThat(commentService.getCommentsForList(marinaId, listId, 1, 10).getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getCommentsForList] Should Return Page - When List Is Followers-Only And Viewer Follows The Owner")
    void shouldReturnPageWhenListIsFollowersOnlyAndViewerFollowsTheOwner() {
        scifi.setVisibility(UserListVisibility.FOLLOWERS);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(commentRepository.findByListIdOrderByCreatedAtAsc(eq(listId), any(PageRequest.class))).thenReturn(Page.empty());

        assertThat(commentService.getCommentsForList(lucasId, listId, 1, 10).getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getCommentsForList] Should Throw ForbiddenException - When List Is Followers-Only And Viewer Does Not Follow The Owner")
    void shouldThrowForbiddenExceptionWhenListIsFollowersOnlyAndViewerDoesNotFollowTheOwner() {
        scifi.setVisibility(UserListVisibility.FOLLOWERS);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> commentService.getCommentsForList(lucasId, listId, 1, 10))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This list is private");

        verifyNoInteractions(commentRepository);
    }

    @Test
    @DisplayName("[getCommentsForList] Should Throw ForbiddenException - When List Is Private And Viewer Is Not The Owner")
    void shouldThrowForbiddenExceptionWhenListIsPrivateAndViewerIsNotTheOwner() {
        scifi.setVisibility(UserListVisibility.PRIVATE);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));

        assertThatThrownBy(() -> commentService.getCommentsForList(lucasId, listId, 1, 10))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This list is private");

        verifyNoInteractions(commentRepository);
    }

    @Test
    @DisplayName("[getCommentsForList] Should Return Empty Page - When List Is A List Of Lists")
    void shouldReturnEmptyPageWhenListIsAListOfLists() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(commentRepository.findByListIdOrderByCreatedAtAsc(eq(listId), any(PageRequest.class))).thenReturn(Page.empty());

        assertThat(commentService.getCommentsForList(lucasId, listId, 1, 10).getContent()).isEmpty();
        verifyNoInteractions(userListItemRepository);
    }

    @Test
    @DisplayName("[getCommentsForList] Should Default Page And Size - When Both Are Null")
    void shouldDefaultPageAndSizeWhenBothAreNullForList() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(commentRepository.findByListIdOrderByCreatedAtAsc(any(), any(PageRequest.class))).thenReturn(Page.empty());

        commentService.getCommentsForList(lucasId, listId, null, null);

        verify(commentRepository).findByListIdOrderByCreatedAtAsc(eq(listId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(CommentServiceImpl.DEFAULT_PAGE);
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(CommentServiceImpl.DEFAULT_PAGE_SIZE);
    }

    // ---------- getCommentsForDiaryEntry ----------

    @Test
    @DisplayName("[getCommentsForDiaryEntry] Should Return Page - When Owner Profile Is Public")
    void shouldReturnPageWhenOwnerProfileIsPublic() {
        Comment comment = buildDiaryEntryComment(diaryEntry);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(commentRepository.findByDiaryEntryIdOrderByCreatedAtAsc(eq(diaryEntryId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(comment)));
        when(commentMapper.commentToResponseDto(comment)).thenReturn(responseDto);

        Page<CommentResponseDTO> result = commentService.getCommentsForDiaryEntry(lucasId, diaryEntryId, 1, 10);

        assertThat(result.getContent()).containsExactly(responseDto);
    }

    @Test
    @DisplayName("[getCommentsForDiaryEntry] Should Return Empty Page - When Diary Entry Has No Comments")
    void shouldReturnEmptyPageWhenDiaryEntryHasNoComments() {
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(commentRepository.findByDiaryEntryIdOrderByCreatedAtAsc(eq(diaryEntryId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<CommentResponseDTO> result = commentService.getCommentsForDiaryEntry(lucasId, diaryEntryId, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verify(commentMapper, never()).commentToResponseDto(any());
    }

    @Test
    @DisplayName("[getCommentsForDiaryEntry] Should Throw NotFoundException - When Diary Entry Does Not Exist")
    void shouldThrowNotFoundExceptionWhenDiaryEntryDoesNotExistForListing() {
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.getCommentsForDiaryEntry(lucasId, diaryEntryId, 1, 10))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");

        verifyNoInteractions(commentRepository);
    }

    @Test
    @DisplayName("[getCommentsForDiaryEntry] Should Return Page - When Owner Profile Is Private And Viewer Is The Owner")
    void shouldReturnPageWhenOwnerProfileIsPrivateAndViewerIsTheOwner() {
        marina.setIsProfilePublic(false);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(commentRepository.findByDiaryEntryIdOrderByCreatedAtAsc(eq(diaryEntryId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        assertThat(commentService.getCommentsForDiaryEntry(marinaId, diaryEntryId, 1, 10).getContent()).isEmpty();
        verifyNoInteractions(followerRepository);
    }

    @Test
    @DisplayName("[getCommentsForDiaryEntry] Should Return Page - When Owner Profile Is Private And Viewer Follows The Owner")
    void shouldReturnPageWhenOwnerProfileIsPrivateAndViewerFollowsTheOwner() {
        marina.setIsProfilePublic(false);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(commentRepository.findByDiaryEntryIdOrderByCreatedAtAsc(eq(diaryEntryId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        assertThat(commentService.getCommentsForDiaryEntry(lucasId, diaryEntryId, 1, 10).getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getCommentsForDiaryEntry] Should Throw ForbiddenException - When Owner Profile Is Private And Viewer Does Not Follow The Owner")
    void shouldThrowForbiddenExceptionWhenOwnerProfileIsPrivateAndViewerDoesNotFollowTheOwner() {
        marina.setIsProfilePublic(false);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> commentService.getCommentsForDiaryEntry(lucasId, diaryEntryId, 1, 10))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This diary entry is private");

        verifyNoInteractions(commentRepository);
    }

    @Test
    @DisplayName("[getCommentsForDiaryEntry] Should Default Page And Size - When Both Are Null")
    void shouldDefaultPageAndSizeWhenBothAreNullForDiaryEntry() {
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(commentRepository.findByDiaryEntryIdOrderByCreatedAtAsc(any(), any(PageRequest.class))).thenReturn(Page.empty());

        commentService.getCommentsForDiaryEntry(lucasId, diaryEntryId, null, null);

        verify(commentRepository).findByDiaryEntryIdOrderByCreatedAtAsc(eq(diaryEntryId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(CommentServiceImpl.DEFAULT_PAGE);
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(CommentServiceImpl.DEFAULT_PAGE_SIZE);
    }

    // ---------- createCommentOnContent ----------

    @Test
    @DisplayName("[createCommentOnContent] Should Save Comment Targeting Content - When Content Exists")
    void shouldSaveCommentTargetingContentWhenContentExists() {
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(fightClub));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.commentToResponseDto(any(Comment.class))).thenReturn(responseDto);

        CommentResponseDTO result = commentService.createCommentOnContent(
                lucasId, contentId, new CommentCreationDTO("Great movie!", null, null));

        assertThat(result).isEqualTo(responseDto);
        verify(commentRepository).save(commentCaptor.capture());
        Comment saved = commentCaptor.getValue();
        assertThat(saved.getContent()).isEqualTo(fightClub);
        assertThat(saved.getList()).isNull();
        assertThat(saved.getDiaryEntry()).isNull();
        assertThat(saved.getParentComment()).isNull();
        assertThat(saved.getUser()).isEqualTo(lucas);
        assertThat(saved.getText()).isEqualTo("Great movie!");
        assertThat(saved.getContainsSpoiler()).isFalse();
    }

    @Test
    @DisplayName("[createCommentOnContent] Should Persist ContainsSpoiler True - When Explicitly Provided")
    void shouldPersistContainsSpoilerTrueWhenExplicitlyProvidedOnContent() {
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(fightClub));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.commentToResponseDto(any(Comment.class))).thenReturn(responseDto);

        commentService.createCommentOnContent(lucasId, contentId, new CommentCreationDTO("Ending spoiler", null, true));

        verify(commentRepository).save(commentCaptor.capture());
        assertThat(commentCaptor.getValue().getContainsSpoiler()).isTrue();
    }

    @Test
    @DisplayName("[createCommentOnContent] Should Throw NotFoundException - When Content Does Not Exist")
    void shouldThrowNotFoundExceptionWhenContentDoesNotExistOnCreate() {
        when(contentRepository.findById(contentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.createCommentOnContent(
                lucasId, contentId, new CommentCreationDTO("text", null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Content not found");

        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[createCommentOnContent] Should Save Reply - When Parent Comment Targets The Same Content")
    void shouldSaveReplyWhenParentCommentTargetsTheSameContent() {
        Comment parent = buildContentComment(fightClub);
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(fightClub));
        when(commentRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.commentToResponseDto(any(Comment.class))).thenReturn(responseDto);

        commentService.createCommentOnContent(lucasId, contentId, new CommentCreationDTO("Totally agree", parent.getId(), null));

        verify(commentRepository).save(commentCaptor.capture());
        assertThat(commentCaptor.getValue().getParentComment()).isEqualTo(parent);
    }

    @Test
    @DisplayName("[createCommentOnContent] Should Throw NotFoundException - When Parent Comment Does Not Exist")
    void shouldThrowNotFoundExceptionWhenParentCommentDoesNotExistOnContent() {
        UUID missingParentId = UUID.randomUUID();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(fightClub));
        when(commentRepository.findById(missingParentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.createCommentOnContent(
                lucasId, contentId, new CommentCreationDTO("Reply", missingParentId, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Parent comment not found");

        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[createCommentOnContent] Should Throw BadRequestException - When Parent Comment Targets A Different Content")
    void shouldThrowBadRequestExceptionWhenParentCommentTargetsADifferentContent() {
        Content pulpFiction = Content.builder()
                .id(UUID.randomUUID())
                .tmdbId("680")
                .type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Comment parentOnOtherContent = buildContentComment(pulpFiction);
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(fightClub));
        when(commentRepository.findById(parentOnOtherContent.getId())).thenReturn(Optional.of(parentOnOtherContent));

        assertThatThrownBy(() -> commentService.createCommentOnContent(
                lucasId, contentId, new CommentCreationDTO("Reply", parentOnOtherContent.getId(), null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Parent comment must target the same content");

        verify(commentRepository, never()).save(any());
    }

    // ---------- createCommentOnList ----------

    @Test
    @DisplayName("[createCommentOnList] Should Save Comment Targeting List - When List Is Public")
    void shouldSaveCommentTargetingListWhenListIsPublic() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.commentToResponseDto(any(Comment.class))).thenReturn(responseDto);

        CommentResponseDTO result = commentService.createCommentOnList(
                lucasId, listId, new CommentCreationDTO("Nice picks", null, null));

        assertThat(result).isEqualTo(responseDto);
        verify(commentRepository).save(commentCaptor.capture());
        Comment saved = commentCaptor.getValue();
        assertThat(saved.getList()).isEqualTo(scifi);
        assertThat(saved.getContent()).isNull();
        assertThat(saved.getDiaryEntry()).isNull();
        assertThat(saved.getUser()).isEqualTo(lucas);
    }

    @Test
    @DisplayName("[createCommentOnList] Should Throw NotFoundException - When List Does Not Exist")
    void shouldThrowNotFoundExceptionWhenListDoesNotExistOnCreate() {
        when(userListRepository.findById(listId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.createCommentOnList(
                lucasId, listId, new CommentCreationDTO("Nice picks", null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[createCommentOnList] Should Save Comment - When List Is Private And Commenter Is The Owner")
    void shouldSaveCommentWhenListIsPrivateAndCommenterIsTheOwner() {
        scifi.setVisibility(UserListVisibility.PRIVATE);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        when(userRepository.getReferenceById(marinaId)).thenReturn(marina);
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.commentToResponseDto(any(Comment.class))).thenReturn(responseDto);

        commentService.createCommentOnList(marinaId, listId, new CommentCreationDTO("My own list", null, null));

        verify(commentRepository).save(any(Comment.class));
    }

    @Test
    @DisplayName("[createCommentOnList] Should Save Comment - When List Is Followers-Only And Commenter Follows The Owner")
    void shouldSaveCommentWhenListIsFollowersOnlyAndCommenterFollowsTheOwner() {
        scifi.setVisibility(UserListVisibility.FOLLOWERS);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.commentToResponseDto(any(Comment.class))).thenReturn(responseDto);

        commentService.createCommentOnList(lucasId, listId, new CommentCreationDTO("Nice picks", null, null));

        verify(commentRepository).save(any(Comment.class));
    }

    @Test
    @DisplayName("[createCommentOnList] Should Throw ForbiddenException - When List Is Followers-Only And Commenter Does Not Follow The Owner")
    void shouldThrowForbiddenExceptionWhenListIsFollowersOnlyAndCommenterDoesNotFollowTheOwnerOnCreate() {
        scifi.setVisibility(UserListVisibility.FOLLOWERS);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> commentService.createCommentOnList(
                lucasId, listId, new CommentCreationDTO("Nice picks", null, null)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This list is private");

        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[createCommentOnList] Should Throw ForbiddenException - When List Is Private And Commenter Is Not The Owner")
    void shouldThrowForbiddenExceptionWhenListIsPrivateAndCommenterIsNotTheOwnerOnCreate() {
        scifi.setVisibility(UserListVisibility.PRIVATE);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));

        assertThatThrownBy(() -> commentService.createCommentOnList(
                lucasId, listId, new CommentCreationDTO("Nice picks", null, null)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This list is private");

        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[createCommentOnList] Should Throw BadRequestException - When List Is Locked As A List Of Lists")
    void shouldThrowBadRequestExceptionWhenListIsLockedAsAListOfLists() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(true);

        assertThatThrownBy(() -> commentService.createCommentOnList(
                lucasId, listId, new CommentCreationDTO("Nice picks", null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("This list is a list of lists and cannot receive comments");

        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[createCommentOnList] Should Save Reply - When Parent Comment Targets The Same List")
    void shouldSaveReplyWhenParentCommentTargetsTheSameList() {
        Comment parent = buildListComment(scifi);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        when(commentRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.commentToResponseDto(any(Comment.class))).thenReturn(responseDto);

        commentService.createCommentOnList(lucasId, listId, new CommentCreationDTO("Totally agree", parent.getId(), null));

        verify(commentRepository).save(commentCaptor.capture());
        assertThat(commentCaptor.getValue().getParentComment()).isEqualTo(parent);
    }

    @Test
    @DisplayName("[createCommentOnList] Should Throw NotFoundException - When Parent Comment Does Not Exist")
    void shouldThrowNotFoundExceptionWhenParentCommentDoesNotExistOnList() {
        UUID missingParentId = UUID.randomUUID();
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        when(commentRepository.findById(missingParentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.createCommentOnList(
                lucasId, listId, new CommentCreationDTO("Reply", missingParentId, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Parent comment not found");

        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[createCommentOnList] Should Throw BadRequestException - When Parent Comment Targets A Different List")
    void shouldThrowBadRequestExceptionWhenParentCommentTargetsADifferentList() {
        UserList horror = UserList.builder()
                .id(UUID.randomUUID())
                .user(marina)
                .name("Underrated horror")
                .visibility(UserListVisibility.PUBLIC)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Comment parentOnOtherList = buildListComment(horror);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        when(commentRepository.findById(parentOnOtherList.getId())).thenReturn(Optional.of(parentOnOtherList));

        assertThatThrownBy(() -> commentService.createCommentOnList(
                lucasId, listId, new CommentCreationDTO("Reply", parentOnOtherList.getId(), null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Parent comment must target the same list");

        verify(commentRepository, never()).save(any());
    }

    // ---------- createCommentOnDiaryEntry ----------

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Save Comment Targeting Diary Entry - When Owner Profile Is Public")
    void shouldSaveCommentTargetingDiaryEntryWhenOwnerProfileIsPublic() {
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.commentToResponseDto(any(Comment.class))).thenReturn(responseDto);

        CommentResponseDTO result = commentService.createCommentOnDiaryEntry(
                lucasId, diaryEntryId, new CommentCreationDTO("Agreed", null, null));

        assertThat(result).isEqualTo(responseDto);
        verify(commentRepository).save(commentCaptor.capture());
        Comment saved = commentCaptor.getValue();
        assertThat(saved.getDiaryEntry()).isEqualTo(diaryEntry);
        assertThat(saved.getContent()).isNull();
        assertThat(saved.getList()).isNull();
        assertThat(saved.getUser()).isEqualTo(lucas);
    }

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Throw NotFoundException - When Diary Entry Does Not Exist")
    void shouldThrowNotFoundExceptionWhenDiaryEntryDoesNotExistOnCreate() {
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.createCommentOnDiaryEntry(
                lucasId, diaryEntryId, new CommentCreationDTO("Agreed", null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");

        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Save Comment - When Owner Profile Is Private And Commenter Is The Owner")
    void shouldSaveCommentWhenOwnerProfileIsPrivateAndCommenterIsTheOwner() {
        marina.setIsProfilePublic(false);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(userRepository.getReferenceById(marinaId)).thenReturn(marina);
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.commentToResponseDto(any(Comment.class))).thenReturn(responseDto);

        commentService.createCommentOnDiaryEntry(marinaId, diaryEntryId, new CommentCreationDTO("My own entry", null, null));

        verify(commentRepository).save(any(Comment.class));
    }

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Save Comment - When Owner Profile Is Private And Commenter Follows The Owner")
    void shouldSaveCommentWhenOwnerProfileIsPrivateAndCommenterFollowsTheOwner() {
        marina.setIsProfilePublic(false);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.commentToResponseDto(any(Comment.class))).thenReturn(responseDto);

        commentService.createCommentOnDiaryEntry(lucasId, diaryEntryId, new CommentCreationDTO("Agreed", null, null));

        verify(commentRepository).save(any(Comment.class));
    }

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Throw ForbiddenException - When Owner Profile Is Private And Commenter Does Not Follow The Owner")
    void shouldThrowForbiddenExceptionWhenOwnerProfileIsPrivateAndCommenterDoesNotFollowTheOwner() {
        marina.setIsProfilePublic(false);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> commentService.createCommentOnDiaryEntry(
                lucasId, diaryEntryId, new CommentCreationDTO("Agreed", null, null)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This diary entry is private");

        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Save Reply - When Parent Comment Targets The Same Diary Entry")
    void shouldSaveReplyWhenParentCommentTargetsTheSameDiaryEntry() {
        Comment parent = buildDiaryEntryComment(diaryEntry);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(commentRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.commentToResponseDto(any(Comment.class))).thenReturn(responseDto);

        commentService.createCommentOnDiaryEntry(
                lucasId, diaryEntryId, new CommentCreationDTO("Totally agree", parent.getId(), null));

        verify(commentRepository).save(commentCaptor.capture());
        assertThat(commentCaptor.getValue().getParentComment()).isEqualTo(parent);
    }

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Throw NotFoundException - When Parent Comment Does Not Exist")
    void shouldThrowNotFoundExceptionWhenParentCommentDoesNotExistOnDiaryEntry() {
        UUID missingParentId = UUID.randomUUID();
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(commentRepository.findById(missingParentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.createCommentOnDiaryEntry(
                lucasId, diaryEntryId, new CommentCreationDTO("Reply", missingParentId, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Parent comment not found");

        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Throw BadRequestException - When Parent Comment Targets A Different Diary Entry")
    void shouldThrowBadRequestExceptionWhenParentCommentTargetsADifferentDiaryEntry() {
        DiaryEntry otherEntry = DiaryEntry.builder()
                .id(UUID.randomUUID())
                .user(marina)
                .content(fightClub)
                .watchNumber(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Comment parentOnOtherEntry = buildDiaryEntryComment(otherEntry);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(commentRepository.findById(parentOnOtherEntry.getId())).thenReturn(Optional.of(parentOnOtherEntry));

        assertThatThrownBy(() -> commentService.createCommentOnDiaryEntry(
                lucasId, diaryEntryId, new CommentCreationDTO("Reply", parentOnOtherEntry.getId(), null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Parent comment must target the same diary entry");

        verify(commentRepository, never()).save(any());
    }

    // ---------- deleteComment ----------

    @Test
    @DisplayName("[deleteComment] Should Delete The Comment - When It Is Owned By The Requester")
    void shouldDeleteTheCommentWhenItIsOwnedByTheRequester() {
        Comment comment = buildContentComment(fightClub);
        when(commentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));

        commentService.deleteComment(lucasId, comment.getId());

        verify(commentRepository).delete(comment);
    }

    @Test
    @DisplayName("[deleteComment] Should Throw NotFoundException - When The Comment Does Not Exist")
    void shouldThrowNotFoundExceptionWhenTheCommentDoesNotExist() {
        UUID commentId = UUID.randomUUID();
        when(commentRepository.findById(commentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.deleteComment(lucasId, commentId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Comment not found");

        verify(commentRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[deleteComment] Should Throw NotFoundException - When The Comment Belongs To Another User")
    void shouldThrowNotFoundExceptionWhenTheCommentBelongsToAnotherUser() {
        Comment comment = buildContentComment(fightClub);
        when(commentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.deleteComment(marinaId, comment.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Comment not found");

        verify(commentRepository, never()).delete(any());
    }
}
