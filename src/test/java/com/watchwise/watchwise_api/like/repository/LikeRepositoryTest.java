package com.watchwise.watchwise_api.like.repository;

import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.like.entity.Like;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class LikeRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private DiaryEntryRepository diaryEntryRepository;

    @Autowired
    private UserListRepository userListRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User lucas;
    private User marina;
    private Content fightClub;
    private Comment comment;
    private DiaryEntry diaryEntry;
    private UserList scifi;

    @BeforeEach
    void setUp() {
        likeRepository.deleteAll();
        commentRepository.deleteAll();
        diaryEntryRepository.deleteAll();
        userListRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();

        lucas = userRepository.save(buildUser("lucas", "lucas@email.com"));
        marina = userRepository.save(buildUser("marina", "marina@email.com"));
        fightClub = contentRepository.save(buildContent("550"));
        comment = commentRepository.save(buildComment(marina, fightClub));
        diaryEntry = diaryEntryRepository.save(buildDiaryEntry(marina, fightClub));
        scifi = userListRepository.save(buildList(marina, "Best sci-fi of the 90s"));
    }

    @Test
    @DisplayName("[save] Should Persist A Like On A Comment - When Comment Is Provided As The Target")
    void shouldPersistALikeOnACommentWhenCommentIsProvidedAsTheTarget() {
        Like saved = likeRepository.saveAndFlush(buildCommentLike(lucas, comment));
        entityManager.clear();

        Like found = likeRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getComment().getId()).isEqualTo(comment.getId());
        assertThat(found.getDiaryEntry()).isNull();
        assertThat(found.getUser().getId()).isEqualTo(lucas.getId());
    }

    @Test
    @DisplayName("[save] Should Persist A Like On A Diary Entry - When Diary Entry Is Provided As The Target")
    void shouldPersistALikeOnADiaryEntryWhenDiaryEntryIsProvidedAsTheTarget() {
        Like saved = likeRepository.saveAndFlush(buildDiaryEntryLike(lucas, diaryEntry));
        entityManager.clear();

        Like found = likeRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getDiaryEntry().getId()).isEqualTo(diaryEntry.getId());
        assertThat(found.getComment()).isNull();
    }

    @Test
    @DisplayName("[save] Should Persist A Like On A List - When List Is Provided As The Target")
    void shouldPersistALikeOnAListWhenListIsProvidedAsTheTarget() {
        Like saved = likeRepository.saveAndFlush(buildListLike(lucas, scifi));
        entityManager.clear();

        Like found = likeRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getList().getId()).isEqualTo(scifi.getId());
        assertThat(found.getComment()).isNull();
        assertThat(found.getDiaryEntry()).isNull();
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When No Target Is Provided")
    void shouldThrowDataIntegrityViolationExceptionWhenNoTargetIsProvided() {
        Like like = Like.builder()
                .user(lucas)
                .createdAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> likeRepository.saveAndFlush(like))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Both Comment And Diary Entry Are Provided")
    void shouldThrowDataIntegrityViolationExceptionWhenBothCommentAndDiaryEntryAreProvided() {
        Like like = Like.builder()
                .user(lucas)
                .comment(comment)
                .diaryEntry(diaryEntry)
                .createdAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> likeRepository.saveAndFlush(like))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Both Comment And List Are Provided")
    void shouldThrowDataIntegrityViolationExceptionWhenBothCommentAndListAreProvided() {
        Like like = Like.builder()
                .user(lucas)
                .comment(comment)
                .list(scifi)
                .createdAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> likeRepository.saveAndFlush(like))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When All Three Targets Are Provided")
    void shouldThrowDataIntegrityViolationExceptionWhenAllThreeTargetsAreProvided() {
        Like like = Like.builder()
                .user(lucas)
                .comment(comment)
                .diaryEntry(diaryEntry)
                .list(scifi)
                .createdAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> likeRepository.saveAndFlush(like))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When The Same User Likes The Same Comment Twice")
    void shouldThrowDataIntegrityViolationExceptionWhenTheSameUserLikesTheSameCommentTwice() {
        likeRepository.saveAndFlush(buildCommentLike(lucas, comment));
        entityManager.clear();

        assertThatThrownBy(() -> likeRepository.saveAndFlush(buildCommentLike(lucas, comment)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When The Same User Likes The Same Diary Entry Twice")
    void shouldThrowDataIntegrityViolationExceptionWhenTheSameUserLikesTheSameDiaryEntryTwice() {
        likeRepository.saveAndFlush(buildDiaryEntryLike(lucas, diaryEntry));
        entityManager.clear();

        assertThatThrownBy(() -> likeRepository.saveAndFlush(buildDiaryEntryLike(lucas, diaryEntry)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When The Same User Likes The Same List Twice")
    void shouldThrowDataIntegrityViolationExceptionWhenTheSameUserLikesTheSameListTwice() {
        likeRepository.saveAndFlush(buildListLike(lucas, scifi));
        entityManager.clear();

        assertThatThrownBy(() -> likeRepository.saveAndFlush(buildListLike(lucas, scifi)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Allow Different Users To Like The Same List")
    void shouldAllowDifferentUsersToLikeTheSameList() {
        likeRepository.saveAndFlush(buildListLike(lucas, scifi));
        entityManager.clear();

        Like saved = likeRepository.saveAndFlush(buildListLike(marina, scifi));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("[save] Should Allow The Same User To Like A Comment And A Diary Entry")
    void shouldAllowTheSameUserToLikeACommentAndADiaryEntry() {
        likeRepository.saveAndFlush(buildCommentLike(lucas, comment));
        entityManager.clear();

        Like saved = likeRepository.saveAndFlush(buildDiaryEntryLike(lucas, diaryEntry));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("[save] Should Allow Different Users To Like The Same Comment")
    void shouldAllowDifferentUsersToLikeTheSameComment() {
        likeRepository.saveAndFlush(buildCommentLike(lucas, comment));
        entityManager.clear();

        Like saved = likeRepository.saveAndFlush(buildCommentLike(marina, comment));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("[deleteByUserIdAndCommentId] Should Delete The Row And Return One - When It Exists")
    void shouldDeleteTheRowAndReturnOneWhenItExistsByUserIdAndCommentId() {
        likeRepository.saveAndFlush(buildCommentLike(lucas, comment));
        entityManager.clear();

        int deleted = likeRepository.deleteByUserIdAndCommentId(lucas.getId(), comment.getId());

        assertThat(deleted).isEqualTo(1);
        assertThat(likeRepository.existsByUserIdAndCommentId(lucas.getId(), comment.getId())).isFalse();
    }

    @Test
    @DisplayName("[deleteByUserIdAndCommentId] Should Return Zero - When It Does Not Exist")
    void shouldReturnZeroWhenItDoesNotExistByUserIdAndCommentId() {
        int deleted = likeRepository.deleteByUserIdAndCommentId(lucas.getId(), comment.getId());

        assertThat(deleted).isZero();
    }

    @Test
    @DisplayName("[deleteByUserIdAndDiaryEntryId] Should Delete The Row And Return One - When It Exists")
    void shouldDeleteTheRowAndReturnOneWhenItExistsByUserIdAndDiaryEntryId() {
        likeRepository.saveAndFlush(buildDiaryEntryLike(lucas, diaryEntry));
        entityManager.clear();

        int deleted = likeRepository.deleteByUserIdAndDiaryEntryId(lucas.getId(), diaryEntry.getId());

        assertThat(deleted).isEqualTo(1);
        assertThat(likeRepository.existsByUserIdAndDiaryEntryId(lucas.getId(), diaryEntry.getId())).isFalse();
    }

    @Test
    @DisplayName("[deleteByUserIdAndDiaryEntryId] Should Return Zero - When It Does Not Exist")
    void shouldReturnZeroWhenItDoesNotExistByUserIdAndDiaryEntryId() {
        int deleted = likeRepository.deleteByUserIdAndDiaryEntryId(lucas.getId(), diaryEntry.getId());

        assertThat(deleted).isZero();
    }

    @Test
    @DisplayName("[existsByUserIdAndCommentId] Should Return True - When It Exists")
    void shouldReturnTrueWhenItExistsByUserIdAndCommentId() {
        likeRepository.saveAndFlush(buildCommentLike(lucas, comment));
        entityManager.clear();

        assertThat(likeRepository.existsByUserIdAndCommentId(lucas.getId(), comment.getId())).isTrue();
    }

    @Test
    @DisplayName("[existsByUserIdAndCommentId] Should Return False - When It Does Not Exist")
    void shouldReturnFalseWhenItDoesNotExistByUserIdAndCommentId() {
        assertThat(likeRepository.existsByUserIdAndCommentId(lucas.getId(), comment.getId())).isFalse();
    }

    @Test
    @DisplayName("[existsByUserIdAndDiaryEntryId] Should Return True - When It Exists")
    void shouldReturnTrueWhenItExistsByUserIdAndDiaryEntryId() {
        likeRepository.saveAndFlush(buildDiaryEntryLike(lucas, diaryEntry));
        entityManager.clear();

        assertThat(likeRepository.existsByUserIdAndDiaryEntryId(lucas.getId(), diaryEntry.getId())).isTrue();
    }

    @Test
    @DisplayName("[existsByUserIdAndDiaryEntryId] Should Return False - When It Does Not Exist")
    void shouldReturnFalseWhenItDoesNotExistByUserIdAndDiaryEntryId() {
        assertThat(likeRepository.existsByUserIdAndDiaryEntryId(lucas.getId(), diaryEntry.getId())).isFalse();
    }

    @Test
    @DisplayName("[deleteByUserIdAndListId] Should Delete The Row And Return One - When It Exists")
    void shouldDeleteTheRowAndReturnOneWhenItExistsByUserIdAndListId() {
        likeRepository.saveAndFlush(buildListLike(lucas, scifi));
        entityManager.clear();

        int deleted = likeRepository.deleteByUserIdAndListId(lucas.getId(), scifi.getId());

        assertThat(deleted).isEqualTo(1);
        assertThat(likeRepository.existsByUserIdAndListId(lucas.getId(), scifi.getId())).isFalse();
    }

    @Test
    @DisplayName("[deleteByUserIdAndListId] Should Return Zero - When It Does Not Exist")
    void shouldReturnZeroWhenItDoesNotExistByUserIdAndListId() {
        int deleted = likeRepository.deleteByUserIdAndListId(lucas.getId(), scifi.getId());

        assertThat(deleted).isZero();
    }

    @Test
    @DisplayName("[existsByUserIdAndListId] Should Return True - When It Exists")
    void shouldReturnTrueWhenItExistsByUserIdAndListId() {
        likeRepository.saveAndFlush(buildListLike(lucas, scifi));
        entityManager.clear();

        assertThat(likeRepository.existsByUserIdAndListId(lucas.getId(), scifi.getId())).isTrue();
    }

    @Test
    @DisplayName("[existsByUserIdAndListId] Should Return False - When It Does Not Exist")
    void shouldReturnFalseWhenItDoesNotExistByUserIdAndListId() {
        assertThat(likeRepository.existsByUserIdAndListId(lucas.getId(), scifi.getId())).isFalse();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Like Rows - When The Target Comment Is Deleted")
    void shouldCascadeDeleteLikeRowsWhenTheTargetCommentIsDeleted() {
        Like saved = likeRepository.saveAndFlush(buildCommentLike(lucas, comment));
        entityManager.clear();

        commentRepository.delete(commentRepository.findById(comment.getId()).orElseThrow());
        commentRepository.flush();

        assertThat(likeRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Like Rows - When The Target Diary Entry Is Deleted")
    void shouldCascadeDeleteLikeRowsWhenTheTargetDiaryEntryIsDeleted() {
        Like saved = likeRepository.saveAndFlush(buildDiaryEntryLike(lucas, diaryEntry));
        entityManager.clear();

        diaryEntryRepository.delete(diaryEntryRepository.findById(diaryEntry.getId()).orElseThrow());
        diaryEntryRepository.flush();

        assertThat(likeRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Like Rows - When The Target List Is Deleted")
    void shouldCascadeDeleteLikeRowsWhenTheTargetListIsDeleted() {
        Like saved = likeRepository.saveAndFlush(buildListLike(lucas, scifi));
        entityManager.clear();

        userListRepository.delete(userListRepository.findById(scifi.getId()).orElseThrow());
        userListRepository.flush();

        assertThat(likeRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Like Rows - When The Liker Is Deleted")
    void shouldCascadeDeleteLikeRowsWhenTheLikerIsDeleted() {
        Like saved = likeRepository.saveAndFlush(buildCommentLike(lucas, comment));
        entityManager.clear();

        userRepository.delete(userRepository.findById(lucas.getId()).orElseThrow());
        userRepository.flush();

        assertThat(likeRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[findLikedCommentIds] Should Return Only The Ids Liked By The Given User - Among The Ids Requested")
    void shouldReturnOnlyTheIdsLikedByTheGivenUserAmongTheIdsRequestedForComments() {
        Comment secondComment = commentRepository.save(buildComment(marina, fightClub));
        Comment thirdComment = commentRepository.save(buildComment(marina, fightClub));
        likeRepository.saveAndFlush(buildCommentLike(lucas, comment));
        likeRepository.saveAndFlush(buildCommentLike(marina, secondComment));
        entityManager.clear();

        Set<UUID> result = likeRepository.findLikedCommentIds(
                lucas.getId(), List.of(comment.getId(), secondComment.getId(), thirdComment.getId()));

        assertThat(result).containsExactly(comment.getId());
    }

    @Test
    @DisplayName("[findLikedCommentIds] Should Return Empty - When The User Liked None Of The Requested Ids")
    void shouldReturnEmptyWhenTheUserLikedNoneOfTheRequestedIdsForComments() {
        Set<UUID> result = likeRepository.findLikedCommentIds(lucas.getId(), List.of(comment.getId()));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findLikedDiaryEntryIds] Should Return Only The Ids Liked By The Given User - Among The Ids Requested")
    void shouldReturnOnlyTheIdsLikedByTheGivenUserAmongTheIdsRequestedForDiaryEntries() {
        DiaryEntry secondEntry = diaryEntryRepository.save(buildDiaryEntry(lucas, fightClub));
        likeRepository.saveAndFlush(buildDiaryEntryLike(lucas, diaryEntry));
        entityManager.clear();

        Set<UUID> result = likeRepository.findLikedDiaryEntryIds(
                lucas.getId(), List.of(diaryEntry.getId(), secondEntry.getId()));

        assertThat(result).containsExactly(diaryEntry.getId());
    }

    @Test
    @DisplayName("[findLikedListIds] Should Return Only The Ids Liked By The Given User - Among The Ids Requested")
    void shouldReturnOnlyTheIdsLikedByTheGivenUserAmongTheIdsRequestedForLists() {
        UserList secondList = userListRepository.save(buildList(marina, "Underrated horror"));
        likeRepository.saveAndFlush(buildListLike(lucas, scifi));
        entityManager.clear();

        Set<UUID> result = likeRepository.findLikedListIds(
                lucas.getId(), List.of(scifi.getId(), secondList.getId()));

        assertThat(result).containsExactly(scifi.getId());
    }

    private Like buildCommentLike(User user, Comment comment) {
        return Like.builder()
                .user(user)
                .comment(comment)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private Like buildDiaryEntryLike(User user, DiaryEntry diaryEntry) {
        return Like.builder()
                .user(user)
                .diaryEntry(diaryEntry)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private Like buildListLike(User user, UserList list) {
        return Like.builder()
                .user(user)
                .list(list)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private UserList buildList(User user, String name) {
        LocalDateTime now = LocalDateTime.now();
        return UserList.builder()
                .user(user)
                .name(name)
                .visibility(UserListVisibility.PUBLIC)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Comment buildComment(User user, Content content) {
        LocalDateTime now = LocalDateTime.now();
        return Comment.builder()
                .user(user)
                .content(content)
                .text("Great movie!")
                .containsSpoiler(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private DiaryEntry buildDiaryEntry(User user, Content content) {
        LocalDateTime now = LocalDateTime.now();
        return DiaryEntry.builder()
                .user(user)
                .content(content)
                .watchNumber(1)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Content buildContent(String tmdbId) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .tmdbId(tmdbId)
                .type(ContentType.MOVIE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private User buildUser(String username, String email) {
        return User.builder()
                .username(username)
                .email(email)
                .password("hashed_password")
                .profilePicture("https://example.com/photo.png")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
