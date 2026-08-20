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
import java.util.Optional;

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

    @PersistenceContext
    private EntityManager entityManager;

    private User lucas;
    private User marina;
    private Content fightClub;
    private Comment comment;
    private DiaryEntry diaryEntry;

    @BeforeEach
    void setUp() {
        likeRepository.deleteAll();
        commentRepository.deleteAll();
        diaryEntryRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();

        lucas = userRepository.save(buildUser("lucas", "lucas@email.com"));
        marina = userRepository.save(buildUser("marina", "marina@email.com"));
        fightClub = contentRepository.save(buildContent("550"));
        comment = commentRepository.save(buildComment(marina, fightClub));
        diaryEntry = diaryEntryRepository.save(buildDiaryEntry(marina, fightClub));
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
    @DisplayName("[findByUserIdAndCommentId] Should Return The Like - When It Exists")
    void shouldReturnTheLikeWhenItExistsByUserIdAndCommentId() {
        Like saved = likeRepository.saveAndFlush(buildCommentLike(lucas, comment));
        entityManager.clear();

        Optional<Like> result = likeRepository.findByUserIdAndCommentId(lucas.getId(), comment.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndCommentId] Should Return Empty - When It Does Not Exist")
    void shouldReturnEmptyWhenItDoesNotExistByUserIdAndCommentId() {
        Optional<Like> result = likeRepository.findByUserIdAndCommentId(lucas.getId(), comment.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findByUserIdAndDiaryEntryId] Should Return The Like - When It Exists")
    void shouldReturnTheLikeWhenItExistsByUserIdAndDiaryEntryId() {
        Like saved = likeRepository.saveAndFlush(buildDiaryEntryLike(lucas, diaryEntry));
        entityManager.clear();

        Optional<Like> result = likeRepository.findByUserIdAndDiaryEntryId(lucas.getId(), diaryEntry.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndDiaryEntryId] Should Return Empty - When It Does Not Exist")
    void shouldReturnEmptyWhenItDoesNotExistByUserIdAndDiaryEntryId() {
        Optional<Like> result = likeRepository.findByUserIdAndDiaryEntryId(lucas.getId(), diaryEntry.getId());

        assertThat(result).isEmpty();
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
    @DisplayName("[deleteAll] Should Cascade Delete Like Rows - When The Liker Is Deleted")
    void shouldCascadeDeleteLikeRowsWhenTheLikerIsDeleted() {
        Like saved = likeRepository.saveAndFlush(buildCommentLike(lucas, comment));
        entityManager.clear();

        userRepository.delete(userRepository.findById(lucas.getId()).orElseThrow());
        userRepository.flush();

        assertThat(likeRepository.findById(saved.getId())).isEmpty();
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
