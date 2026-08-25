package com.watchwise.watchwise_api.like.service.impl;

import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.entity.Like;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
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
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikeServiceImplTest {

    @Mock
    private LikeRepository likeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private UserListRepository userListRepository;

    @Mock
    private UserListItemRepository userListItemRepository;

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private NewTransactionExecutor newTransactionExecutor;

    @InjectMocks
    private LikeServiceImpl likeService;

    @Captor
    private ArgumentCaptor<Like> likeCaptor;

    private UUID lucasId;
    private UUID marinaId;
    private User lucas;
    private User marina;
    private Content fightClub;
    private UUID listId;
    private UserList scifi;
    private UUID diaryEntryId;
    private DiaryEntry diaryEntry;
    private UUID commentId;

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

        fightClub = Content.builder()
                .id(UUID.randomUUID())
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

        commentId = UUID.randomUUID();

        lenient().when(newTransactionExecutor.runInNewTransaction(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    private Comment buildContentComment() {
        return Comment.builder()
                .id(commentId)
                .user(marina)
                .content(fightClub)
                .text("Great movie!")
                .containsSpoiler(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Comment buildListComment(UserList list) {
        return Comment.builder()
                .id(commentId)
                .user(marina)
                .list(list)
                .text("Nice picks")
                .containsSpoiler(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Comment buildDiaryEntryComment(DiaryEntry entry) {
        return Comment.builder()
                .id(commentId)
                .user(lucas)
                .diaryEntry(entry)
                .text("Agreed")
                .containsSpoiler(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Comment buildReplyToContentComment() {
        Comment parent = Comment.builder()
                .id(UUID.randomUUID())
                .user(marina)
                .content(fightClub)
                .text("Great movie!")
                .containsSpoiler(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        return Comment.builder()
                .id(commentId)
                .user(lucas)
                .content(fightClub)
                .parentComment(parent)
                .text("Totally agree")
                .containsSpoiler(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ---------- likeComment ----------

    @Test
    @DisplayName("[likeComment] Should Save New Like - When Not Already Liked And Comment Targets Content")
    void shouldSaveNewLikeWhenNotAlreadyLikedAndCommentTargetsContent() {
        when(likeRepository.existsByUserIdAndCommentId(lucasId, commentId)).thenReturn(false);
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.of(buildContentComment()));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.getReferenceById(commentId)).thenReturn(buildContentComment());

        likeService.likeComment(lucasId, commentId);

        verify(likeRepository).saveAndFlush(likeCaptor.capture());
        assertThat(likeCaptor.getValue().getUser()).isEqualTo(lucas);
        assertThat(likeCaptor.getValue().getComment()).isNotNull();
        assertThat(likeCaptor.getValue().getDiaryEntry()).isNull();
        assertThat(likeCaptor.getValue().getCreatedAt()).isNotNull();
        verify(commentRepository).incrementLikesCount(commentId);
    }

    @Test
    @DisplayName("[likeComment] Should Attempt Save In A New Transaction - When Not Already Liked")
    void shouldAttemptSaveInANewTransactionWhenNotAlreadyLikedComment() {
        when(likeRepository.existsByUserIdAndCommentId(lucasId, commentId)).thenReturn(false);
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.of(buildContentComment()));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.getReferenceById(commentId)).thenReturn(buildContentComment());

        likeService.likeComment(lucasId, commentId);

        verify(newTransactionExecutor).runInNewTransaction(any());
    }

    @Test
    @DisplayName("[likeComment] Should Do Nothing - When Already Liked That Comment")
    void shouldDoNothingWhenAlreadyLikedThatComment() {
        when(likeRepository.existsByUserIdAndCommentId(lucasId, commentId)).thenReturn(true);

        likeService.likeComment(lucasId, commentId);

        verify(likeRepository, never()).saveAndFlush(any());
        verifyNoInteractions(commentRepository);
    }

    @Test
    @DisplayName("[likeComment] Should Not Increment LikesCount - When Save Throws DataIntegrityViolationException")
    void shouldNotIncrementLikesCountWhenSaveThrowsDataIntegrityViolationExceptionForComment() {
        when(likeRepository.existsByUserIdAndCommentId(lucasId, commentId))
                .thenReturn(false)
                .thenReturn(true);
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.of(buildContentComment()));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.getReferenceById(commentId)).thenReturn(buildContentComment());
        when(likeRepository.saveAndFlush(any(Like.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        likeService.likeComment(lucasId, commentId);

        verify(commentRepository, never()).incrementLikesCount(any());
    }

    @Test
    @DisplayName("[likeComment] Should Throw NotFoundException - When Comment Does Not Exist")
    void shouldThrowNotFoundExceptionWhenCommentDoesNotExist() {
        when(likeRepository.existsByUserIdAndCommentId(lucasId, commentId)).thenReturn(false);
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> likeService.likeComment(lucasId, commentId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Comment not found");

        verify(likeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[likeComment] Should Save - When The Comment Is A Reply To Another Comment")
    void shouldSaveWhenTheCommentIsAReplyToAnotherComment() {
        Comment reply = buildReplyToContentComment();
        when(likeRepository.existsByUserIdAndCommentId(lucasId, commentId)).thenReturn(false);
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.of(reply));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.getReferenceById(commentId)).thenReturn(reply);

        likeService.likeComment(lucasId, commentId);

        verify(likeRepository).saveAndFlush(any(Like.class));
    }

    @Test
    @DisplayName("[likeComment] Should Save - When Comment Targets A Private List And Liker Is The Owner")
    void shouldSaveWhenCommentTargetsAPrivateListAndLikerIsTheOwner() {
        scifi.setVisibility(UserListVisibility.PRIVATE);
        Comment comment = buildListComment(scifi);
        when(likeRepository.existsByUserIdAndCommentId(marinaId, commentId)).thenReturn(false);
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.of(comment));
        when(userRepository.getReferenceById(marinaId)).thenReturn(marina);
        when(commentRepository.getReferenceById(commentId)).thenReturn(comment);

        likeService.likeComment(marinaId, commentId);

        verify(likeRepository).saveAndFlush(any(Like.class));
    }

    @Test
    @DisplayName("[likeComment] Should Throw ForbiddenException - When Comment Targets A Private List And Liker Is Not The Owner")
    void shouldThrowForbiddenExceptionWhenCommentTargetsAPrivateListAndLikerIsNotTheOwner() {
        scifi.setVisibility(UserListVisibility.PRIVATE);
        Comment comment = buildListComment(scifi);
        when(likeRepository.existsByUserIdAndCommentId(lucasId, commentId)).thenReturn(false);
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> likeService.likeComment(lucasId, commentId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This list is private");

        verify(likeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[likeComment] Should Save - When Comment Targets A Followers-Only List And Liker Follows The Owner")
    void shouldSaveWhenCommentTargetsAFollowersOnlyListAndLikerFollowsTheOwner() {
        scifi.setVisibility(UserListVisibility.FOLLOWERS);
        Comment comment = buildListComment(scifi);
        when(likeRepository.existsByUserIdAndCommentId(lucasId, commentId)).thenReturn(false);
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.of(comment));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.getReferenceById(commentId)).thenReturn(comment);

        likeService.likeComment(lucasId, commentId);

        verify(likeRepository).saveAndFlush(any(Like.class));
    }

    @Test
    @DisplayName("[likeComment] Should Throw ForbiddenException - When Comment Targets A Followers-Only List And Liker Does Not Follow The Owner")
    void shouldThrowForbiddenExceptionWhenCommentTargetsAFollowersOnlyListAndLikerDoesNotFollowTheOwner() {
        scifi.setVisibility(UserListVisibility.FOLLOWERS);
        Comment comment = buildListComment(scifi);
        when(likeRepository.existsByUserIdAndCommentId(lucasId, commentId)).thenReturn(false);
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.of(comment));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> likeService.likeComment(lucasId, commentId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This list is private");

        verify(likeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[likeComment] Should Save - When Comment Targets A Diary Entry And Owner Profile Is Private But Liker Is The Owner")
    void shouldSaveWhenCommentTargetsADiaryEntryAndOwnerProfileIsPrivateButLikerIsTheOwner() {
        marina.setIsProfilePublic(false);
        Comment comment = buildDiaryEntryComment(diaryEntry);
        when(likeRepository.existsByUserIdAndCommentId(marinaId, commentId)).thenReturn(false);
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.of(comment));
        when(userRepository.getReferenceById(marinaId)).thenReturn(marina);
        when(commentRepository.getReferenceById(commentId)).thenReturn(comment);

        likeService.likeComment(marinaId, commentId);

        verify(likeRepository).saveAndFlush(any(Like.class));
    }

    @Test
    @DisplayName("[likeComment] Should Save - When Comment Targets A Diary Entry And Owner Profile Is Private But Liker Follows The Owner")
    void shouldSaveWhenCommentTargetsADiaryEntryAndOwnerProfileIsPrivateButLikerFollowsTheOwner() {
        marina.setIsProfilePublic(false);
        Comment comment = buildDiaryEntryComment(diaryEntry);
        when(likeRepository.existsByUserIdAndCommentId(lucasId, commentId)).thenReturn(false);
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.of(comment));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.getReferenceById(commentId)).thenReturn(comment);

        likeService.likeComment(lucasId, commentId);

        verify(likeRepository).saveAndFlush(any(Like.class));
    }

    @Test
    @DisplayName("[likeComment] Should Throw ForbiddenException - When Comment Targets A Diary Entry And Owner Profile Is Private And Liker Does Not Follow The Owner")
    void shouldThrowForbiddenExceptionWhenCommentTargetsADiaryEntryAndOwnerProfileIsPrivateAndLikerDoesNotFollowTheOwner() {
        marina.setIsProfilePublic(false);
        Comment comment = buildDiaryEntryComment(diaryEntry);
        when(likeRepository.existsByUserIdAndCommentId(lucasId, commentId)).thenReturn(false);
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.of(comment));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> likeService.likeComment(lucasId, commentId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This diary entry is private");

        verify(likeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[likeComment] Should Resolve Successfully - When Save Throws DataIntegrityViolationException But Row Now Exists")
    void shouldResolveSuccessfullyWhenSaveThrowsDataIntegrityViolationExceptionButRowNowExistsForComment() {
        when(likeRepository.existsByUserIdAndCommentId(lucasId, commentId))
                .thenReturn(false)
                .thenReturn(true);
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.of(buildContentComment()));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.getReferenceById(commentId)).thenReturn(buildContentComment());
        when(likeRepository.saveAndFlush(any(Like.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatCode(() -> likeService.likeComment(lucasId, commentId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[likeComment] Should Rethrow DataIntegrityViolationException - When Row Still Does Not Exist After Save Fails")
    void shouldRethrowDataIntegrityViolationExceptionWhenRowStillDoesNotExistAfterSaveFailsForComment() {
        when(likeRepository.existsByUserIdAndCommentId(lucasId, commentId)).thenReturn(false);
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.of(buildContentComment()));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(commentRepository.getReferenceById(commentId)).thenReturn(buildContentComment());
        DataIntegrityViolationException exception = new DataIntegrityViolationException("unexpected db error");
        when(likeRepository.saveAndFlush(any(Like.class))).thenThrow(exception);

        assertThatThrownBy(() -> likeService.likeComment(lucasId, commentId)).isSameAs(exception);
    }

    // ---------- unlikeComment ----------

    @Test
    @DisplayName("[unlikeComment] Should Decrement Likes Count - When A Row Was Actually Deleted")
    void shouldDecrementLikesCountWhenARowWasActuallyDeletedForComment() {
        when(likeRepository.deleteByUserIdAndCommentId(lucasId, commentId)).thenReturn(1);

        likeService.unlikeComment(lucasId, commentId);

        verify(commentRepository).decrementLikesCount(commentId);
    }

    @Test
    @DisplayName("[unlikeComment] Should Not Decrement Likes Count - When No Row Was Deleted, Not Liked Or Already Unliked By A Concurrent Request")
    void shouldNotDecrementLikesCountWhenNoRowWasDeletedForComment() {
        when(likeRepository.deleteByUserIdAndCommentId(lucasId, commentId)).thenReturn(0);

        likeService.unlikeComment(lucasId, commentId);

        verify(commentRepository, never()).decrementLikesCount(any());
    }

    // ---------- likeDiaryEntry ----------

    @Test
    @DisplayName("[likeDiaryEntry] Should Save New Like - When Not Already Liked And Owner Profile Is Public")
    void shouldSaveNewLikeWhenNotAlreadyLikedAndOwnerProfileIsPublic() {
        when(likeRepository.existsByUserIdAndDiaryEntryId(lucasId, diaryEntryId)).thenReturn(false);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(diaryEntryRepository.getReferenceById(diaryEntryId)).thenReturn(diaryEntry);

        likeService.likeDiaryEntry(lucasId, diaryEntryId);

        verify(likeRepository).saveAndFlush(likeCaptor.capture());
        assertThat(likeCaptor.getValue().getUser()).isEqualTo(lucas);
        assertThat(likeCaptor.getValue().getDiaryEntry()).isEqualTo(diaryEntry);
        assertThat(likeCaptor.getValue().getComment()).isNull();
        assertThat(likeCaptor.getValue().getCreatedAt()).isNotNull();
        verify(diaryEntryRepository).incrementLikesCount(diaryEntryId);
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Attempt Save In A New Transaction - When Not Already Liked")
    void shouldAttemptSaveInANewTransactionWhenNotAlreadyLikedDiaryEntry() {
        when(likeRepository.existsByUserIdAndDiaryEntryId(lucasId, diaryEntryId)).thenReturn(false);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(diaryEntryRepository.getReferenceById(diaryEntryId)).thenReturn(diaryEntry);

        likeService.likeDiaryEntry(lucasId, diaryEntryId);

        verify(newTransactionExecutor).runInNewTransaction(any());
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Do Nothing - When Already Liked That Diary Entry")
    void shouldDoNothingWhenAlreadyLikedThatDiaryEntry() {
        when(likeRepository.existsByUserIdAndDiaryEntryId(lucasId, diaryEntryId)).thenReturn(true);

        likeService.likeDiaryEntry(lucasId, diaryEntryId);

        verify(likeRepository, never()).saveAndFlush(any());
        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Not Increment LikesCount - When Save Throws DataIntegrityViolationException")
    void shouldNotIncrementLikesCountWhenSaveThrowsDataIntegrityViolationExceptionForDiaryEntry() {
        when(likeRepository.existsByUserIdAndDiaryEntryId(lucasId, diaryEntryId))
                .thenReturn(false)
                .thenReturn(true);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(diaryEntryRepository.getReferenceById(diaryEntryId)).thenReturn(diaryEntry);
        when(likeRepository.saveAndFlush(any(Like.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        likeService.likeDiaryEntry(lucasId, diaryEntryId);

        verify(diaryEntryRepository, never()).incrementLikesCount(any());
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Throw NotFoundException - When Diary Entry Does Not Exist")
    void shouldThrowNotFoundExceptionWhenDiaryEntryDoesNotExist() {
        when(likeRepository.existsByUserIdAndDiaryEntryId(lucasId, diaryEntryId)).thenReturn(false);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> likeService.likeDiaryEntry(lucasId, diaryEntryId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");

        verify(likeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Save - When Owner Profile Is Private And Liker Is The Owner")
    void shouldSaveWhenOwnerProfileIsPrivateAndLikerIsTheOwner() {
        marina.setIsProfilePublic(false);
        when(likeRepository.existsByUserIdAndDiaryEntryId(marinaId, diaryEntryId)).thenReturn(false);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(userRepository.getReferenceById(marinaId)).thenReturn(marina);
        when(diaryEntryRepository.getReferenceById(diaryEntryId)).thenReturn(diaryEntry);

        likeService.likeDiaryEntry(marinaId, diaryEntryId);

        verify(likeRepository).saveAndFlush(any(Like.class));
        verifyNoInteractions(followerRepository);
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Save - When Owner Profile Is Private And Liker Follows The Owner")
    void shouldSaveWhenOwnerProfileIsPrivateAndLikerFollowsTheOwner() {
        marina.setIsProfilePublic(false);
        when(likeRepository.existsByUserIdAndDiaryEntryId(lucasId, diaryEntryId)).thenReturn(false);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(diaryEntryRepository.getReferenceById(diaryEntryId)).thenReturn(diaryEntry);

        likeService.likeDiaryEntry(lucasId, diaryEntryId);

        verify(likeRepository).saveAndFlush(any(Like.class));
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Throw ForbiddenException - When Owner Profile Is Private And Liker Does Not Follow The Owner")
    void shouldThrowForbiddenExceptionWhenOwnerProfileIsPrivateAndLikerDoesNotFollowTheOwner() {
        marina.setIsProfilePublic(false);
        when(likeRepository.existsByUserIdAndDiaryEntryId(lucasId, diaryEntryId)).thenReturn(false);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> likeService.likeDiaryEntry(lucasId, diaryEntryId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This diary entry is private");

        verify(likeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Resolve Successfully - When Save Throws DataIntegrityViolationException But Row Now Exists")
    void shouldResolveSuccessfullyWhenSaveThrowsDataIntegrityViolationExceptionButRowNowExistsForDiaryEntry() {
        when(likeRepository.existsByUserIdAndDiaryEntryId(lucasId, diaryEntryId))
                .thenReturn(false)
                .thenReturn(true);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(diaryEntryRepository.getReferenceById(diaryEntryId)).thenReturn(diaryEntry);
        when(likeRepository.saveAndFlush(any(Like.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatCode(() -> likeService.likeDiaryEntry(lucasId, diaryEntryId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Rethrow DataIntegrityViolationException - When Row Still Does Not Exist After Save Fails")
    void shouldRethrowDataIntegrityViolationExceptionWhenRowStillDoesNotExistAfterSaveFailsForDiaryEntry() {
        when(likeRepository.existsByUserIdAndDiaryEntryId(lucasId, diaryEntryId)).thenReturn(false);
        when(diaryEntryRepository.findByIdWithUser(diaryEntryId)).thenReturn(Optional.of(diaryEntry));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(diaryEntryRepository.getReferenceById(diaryEntryId)).thenReturn(diaryEntry);
        DataIntegrityViolationException exception = new DataIntegrityViolationException("unexpected db error");
        when(likeRepository.saveAndFlush(any(Like.class))).thenThrow(exception);

        assertThatThrownBy(() -> likeService.likeDiaryEntry(lucasId, diaryEntryId)).isSameAs(exception);
    }

    // ---------- unlikeDiaryEntry ----------

    @Test
    @DisplayName("[unlikeDiaryEntry] Should Decrement Likes Count - When A Row Was Actually Deleted")
    void shouldDecrementLikesCountWhenARowWasActuallyDeletedForDiaryEntry() {
        when(likeRepository.deleteByUserIdAndDiaryEntryId(lucasId, diaryEntryId)).thenReturn(1);

        likeService.unlikeDiaryEntry(lucasId, diaryEntryId);

        verify(diaryEntryRepository).decrementLikesCount(diaryEntryId);
    }

    @Test
    @DisplayName("[unlikeDiaryEntry] Should Not Decrement Likes Count - When No Row Was Deleted, Not Liked Or Already Unliked By A Concurrent Request")
    void shouldNotDecrementLikesCountWhenNoRowWasDeletedForDiaryEntry() {
        when(likeRepository.deleteByUserIdAndDiaryEntryId(lucasId, diaryEntryId)).thenReturn(0);

        likeService.unlikeDiaryEntry(lucasId, diaryEntryId);

        verify(diaryEntryRepository, never()).decrementLikesCount(any());
    }

    // ---------- likeList ----------

    @Test
    @DisplayName("[likeList] Should Save New Like - When Not Already Liked And List Is Public")
    void shouldSaveNewLikeWhenNotAlreadyLikedAndListIsPublic() {
        when(likeRepository.existsByUserIdAndListId(lucasId, listId)).thenReturn(false);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.getReferenceById(listId)).thenReturn(scifi);

        likeService.likeList(lucasId, listId);

        verify(likeRepository).saveAndFlush(likeCaptor.capture());
        assertThat(likeCaptor.getValue().getUser()).isEqualTo(lucas);
        assertThat(likeCaptor.getValue().getList()).isEqualTo(scifi);
        assertThat(likeCaptor.getValue().getComment()).isNull();
        assertThat(likeCaptor.getValue().getDiaryEntry()).isNull();
        assertThat(likeCaptor.getValue().getCreatedAt()).isNotNull();
        verify(userListRepository).incrementLikesCount(listId);
    }

    @Test
    @DisplayName("[likeList] Should Attempt Save In A New Transaction - When Not Already Liked")
    void shouldAttemptSaveInANewTransactionWhenNotAlreadyLikedList() {
        when(likeRepository.existsByUserIdAndListId(lucasId, listId)).thenReturn(false);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.getReferenceById(listId)).thenReturn(scifi);

        likeService.likeList(lucasId, listId);

        verify(newTransactionExecutor).runInNewTransaction(any());
    }

    @Test
    @DisplayName("[likeList] Should Do Nothing - When Already Liked That List")
    void shouldDoNothingWhenAlreadyLikedThatList() {
        when(likeRepository.existsByUserIdAndListId(lucasId, listId)).thenReturn(true);

        likeService.likeList(lucasId, listId);

        verify(likeRepository, never()).saveAndFlush(any());
        verifyNoInteractions(userListRepository);
    }

    @Test
    @DisplayName("[likeList] Should Throw NotFoundException - When List Does Not Exist")
    void shouldThrowNotFoundExceptionWhenListDoesNotExistOnLike() {
        when(likeRepository.existsByUserIdAndListId(lucasId, listId)).thenReturn(false);
        when(userListRepository.findById(listId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> likeService.likeList(lucasId, listId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verify(likeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[likeList] Should Save - When List Is Private And Liker Is The Owner")
    void shouldSaveWhenListIsPrivateAndLikerIsTheOwner() {
        scifi.setVisibility(UserListVisibility.PRIVATE);
        when(likeRepository.existsByUserIdAndListId(marinaId, listId)).thenReturn(false);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        when(userRepository.getReferenceById(marinaId)).thenReturn(marina);
        when(userListRepository.getReferenceById(listId)).thenReturn(scifi);

        likeService.likeList(marinaId, listId);

        verify(likeRepository).saveAndFlush(any(Like.class));
    }

    @Test
    @DisplayName("[likeList] Should Save - When List Is Followers-Only And Liker Follows The Owner")
    void shouldSaveWhenListIsFollowersOnlyAndLikerFollowsTheOwner() {
        scifi.setVisibility(UserListVisibility.FOLLOWERS);
        when(likeRepository.existsByUserIdAndListId(lucasId, listId)).thenReturn(false);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.getReferenceById(listId)).thenReturn(scifi);

        likeService.likeList(lucasId, listId);

        verify(likeRepository).saveAndFlush(any(Like.class));
    }

    @Test
    @DisplayName("[likeList] Should Throw ForbiddenException - When List Is Private And Liker Is Not The Owner")
    void shouldThrowForbiddenExceptionWhenListIsPrivateAndLikerIsNotTheOwner() {
        scifi.setVisibility(UserListVisibility.PRIVATE);
        when(likeRepository.existsByUserIdAndListId(lucasId, listId)).thenReturn(false);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));

        assertThatThrownBy(() -> likeService.likeList(lucasId, listId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This list is private");

        verify(likeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[likeList] Should Throw ForbiddenException - When List Is Followers-Only And Liker Does Not Follow The Owner")
    void shouldThrowForbiddenExceptionWhenListIsFollowersOnlyAndLikerDoesNotFollowTheOwner() {
        scifi.setVisibility(UserListVisibility.FOLLOWERS);
        when(likeRepository.existsByUserIdAndListId(lucasId, listId)).thenReturn(false);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> likeService.likeList(lucasId, listId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This list is private");

        verify(likeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[likeList] Should Throw BadRequestException - When The List Is A List Of Lists")
    void shouldThrowBadRequestExceptionWhenTheListIsAListOfLists() {
        when(likeRepository.existsByUserIdAndListId(marinaId, listId)).thenReturn(false);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(true);

        assertThatThrownBy(() -> likeService.likeList(marinaId, listId))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("This list is a list of lists and cannot receive likes");

        verify(likeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[likeList] Should Resolve Successfully - When Save Throws DataIntegrityViolationException But Row Now Exists")
    void shouldResolveSuccessfullyWhenSaveThrowsDataIntegrityViolationExceptionButRowNowExistsForList() {
        when(likeRepository.existsByUserIdAndListId(lucasId, listId))
                .thenReturn(false)
                .thenReturn(true);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.getReferenceById(listId)).thenReturn(scifi);
        when(likeRepository.saveAndFlush(any(Like.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatCode(() -> likeService.likeList(lucasId, listId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[likeList] Should Rethrow DataIntegrityViolationException - When Row Still Does Not Exist After Save Fails")
    void shouldRethrowDataIntegrityViolationExceptionWhenRowStillDoesNotExistAfterSaveFailsForList() {
        when(likeRepository.existsByUserIdAndListId(lucasId, listId)).thenReturn(false);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.getReferenceById(listId)).thenReturn(scifi);
        DataIntegrityViolationException exception = new DataIntegrityViolationException("unexpected db error");
        when(likeRepository.saveAndFlush(any(Like.class))).thenThrow(exception);

        assertThatThrownBy(() -> likeService.likeList(lucasId, listId)).isSameAs(exception);
    }

    @Test
    @DisplayName("[likeList] Should Not Increment LikesCount - When Save Throws DataIntegrityViolationException")
    void shouldNotIncrementLikesCountWhenSaveThrowsDataIntegrityViolationExceptionForList() {
        when(likeRepository.existsByUserIdAndListId(lucasId, listId))
                .thenReturn(false)
                .thenReturn(true);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.getReferenceById(listId)).thenReturn(scifi);
        when(likeRepository.saveAndFlush(any(Like.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        likeService.likeList(lucasId, listId);

        verify(userListRepository, never()).incrementLikesCount(any());
    }

    // ---------- unlikeList ----------

    @Test
    @DisplayName("[unlikeList] Should Decrement Likes Count - When A Row Was Actually Deleted")
    void shouldDecrementLikesCountWhenARowWasActuallyDeletedForList() {
        when(likeRepository.deleteByUserIdAndListId(lucasId, listId)).thenReturn(1);

        likeService.unlikeList(lucasId, listId);

        verify(userListRepository).decrementLikesCount(listId);
    }

    @Test
    @DisplayName("[unlikeList] Should Not Decrement Likes Count - When No Row Was Deleted, Not Liked Or Already Unliked By A Concurrent Request")
    void shouldNotDecrementLikesCountWhenNoRowWasDeletedForList() {
        when(likeRepository.deleteByUserIdAndListId(lucasId, listId)).thenReturn(0);

        likeService.unlikeList(lucasId, listId);

        verify(userListRepository, never()).decrementLikesCount(any());
    }

    // ---------- getLikedCommentIds / getLikedDiaryEntryIds / getLikedListIds ----------

    @Test
    @DisplayName("[getLikedCommentIds] Should Return Ids From The Repository - When The Id Collection Is Not Empty")
    void shouldReturnIdsFromTheRepositoryWhenTheIdCollectionIsNotEmptyForComments() {
        UUID otherCommentId = UUID.randomUUID();
        when(likeRepository.findLikedCommentIds(lucasId, List.of(commentId, otherCommentId)))
                .thenReturn(Set.of(commentId));

        Set<UUID> result = likeService.getLikedCommentIds(lucasId, List.of(commentId, otherCommentId));

        assertThat(result).containsExactly(commentId);
    }

    @Test
    @DisplayName("[getLikedCommentIds] Should Return Empty Set Without Querying - When The Id Collection Is Empty")
    void shouldReturnEmptySetWithoutQueryingWhenTheIdCollectionIsEmptyForComments() {
        Set<UUID> result = likeService.getLikedCommentIds(lucasId, List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(likeRepository);
    }

    @Test
    @DisplayName("[getLikedDiaryEntryIds] Should Return Ids From The Repository - When The Id Collection Is Not Empty")
    void shouldReturnIdsFromTheRepositoryWhenTheIdCollectionIsNotEmptyForDiaryEntries() {
        when(likeRepository.findLikedDiaryEntryIds(lucasId, List.of(diaryEntryId))).thenReturn(Set.of(diaryEntryId));

        Set<UUID> result = likeService.getLikedDiaryEntryIds(lucasId, List.of(diaryEntryId));

        assertThat(result).containsExactly(diaryEntryId);
    }

    @Test
    @DisplayName("[getLikedDiaryEntryIds] Should Return Empty Set Without Querying - When The Id Collection Is Empty")
    void shouldReturnEmptySetWithoutQueryingWhenTheIdCollectionIsEmptyForDiaryEntries() {
        Set<UUID> result = likeService.getLikedDiaryEntryIds(lucasId, List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(likeRepository);
    }

    @Test
    @DisplayName("[getLikedListIds] Should Return Ids From The Repository - When The Id Collection Is Not Empty")
    void shouldReturnIdsFromTheRepositoryWhenTheIdCollectionIsNotEmptyForLists() {
        when(likeRepository.findLikedListIds(lucasId, List.of(listId))).thenReturn(Set.of(listId));

        Set<UUID> result = likeService.getLikedListIds(lucasId, List.of(listId));

        assertThat(result).containsExactly(listId);
    }

    @Test
    @DisplayName("[getLikedListIds] Should Return Empty Set Without Querying - When The Id Collection Is Empty")
    void shouldReturnEmptySetWithoutQueryingWhenTheIdCollectionIsEmptyForLists() {
        Set<UUID> result = likeService.getLikedListIds(lucasId, List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(likeRepository);
    }
}
