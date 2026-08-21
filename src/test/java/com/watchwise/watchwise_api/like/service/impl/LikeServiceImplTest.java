package com.watchwise.watchwise_api.like.service.impl;

import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
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
import java.util.Optional;
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
    @DisplayName("[unlikeComment] Should Delete The Row - When It Exists")
    void shouldDeleteTheRowWhenItExists() {
        Like like = Like.builder()
                .id(UUID.randomUUID())
                .user(lucas)
                .comment(buildContentComment())
                .createdAt(LocalDateTime.now())
                .build();
        when(likeRepository.findByUserIdAndCommentId(lucasId, commentId)).thenReturn(Optional.of(like));

        likeService.unlikeComment(lucasId, commentId);

        verify(likeRepository).delete(like);
    }

    @Test
    @DisplayName("[unlikeComment] Should Do Nothing - When The Row Does Not Exist")
    void shouldDoNothingWhenTheRowDoesNotExistForComment() {
        when(likeRepository.findByUserIdAndCommentId(lucasId, commentId)).thenReturn(Optional.empty());

        likeService.unlikeComment(lucasId, commentId);

        verify(likeRepository, never()).delete(any());
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
}
