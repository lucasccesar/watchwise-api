package com.watchwise.watchwise_api.comment.repository;

import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CommentRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private UserListRepository userListRepository;

    @Autowired
    private DiaryEntryRepository diaryEntryRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User lucas;
    private User marina;
    private Content fightClub;
    private UserList scifi;
    private DiaryEntry diaryEntry;

    @BeforeEach
    void setUp() {
        commentRepository.deleteAll();
        diaryEntryRepository.deleteAll();
        userListRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();

        lucas = userRepository.save(buildUser("lucas", "lucas@email.com"));
        marina = userRepository.save(buildUser("marina", "marina@email.com"));
        fightClub = contentRepository.save(buildContent("550", ContentType.MOVIE));
        scifi = userListRepository.save(buildList(lucas, "Best sci-fi of the 90s"));
        diaryEntry = diaryEntryRepository.save(buildDiaryEntry(lucas, fightClub));
    }

    @Test
    @DisplayName("[save] Should Persist A Comment On Content - When Content Is Provided As The Target")
    void shouldPersistACommentOnContentWhenContentIsProvidedAsTheTarget() {
        Comment saved = commentRepository.saveAndFlush(buildContentComment(lucas, fightClub, "Great movie!"));
        entityManager.clear();

        Comment found = commentRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getContent().getId()).isEqualTo(fightClub.getId());
        assertThat(found.getList()).isNull();
        assertThat(found.getDiaryEntry()).isNull();
        assertThat(found.getText()).isEqualTo("Great movie!");
        assertThat(found.getContainsSpoiler()).isFalse();
    }

    @Test
    @DisplayName("[save] Should Persist A Comment On A List - When List Is Provided As The Target")
    void shouldPersistACommentOnAListWhenListIsProvidedAsTheTarget() {
        Comment saved = commentRepository.saveAndFlush(buildListComment(lucas, scifi, "Nice picks"));
        entityManager.clear();

        Comment found = commentRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getList().getId()).isEqualTo(scifi.getId());
        assertThat(found.getContent()).isNull();
        assertThat(found.getDiaryEntry()).isNull();
    }

    @Test
    @DisplayName("[save] Should Persist A Comment On A Diary Entry - When Diary Entry Is Provided As The Target")
    void shouldPersistACommentOnADiaryEntryWhenDiaryEntryIsProvidedAsTheTarget() {
        Comment saved = commentRepository.saveAndFlush(buildDiaryEntryComment(lucas, diaryEntry, "Agreed"));
        entityManager.clear();

        Comment found = commentRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getDiaryEntry().getId()).isEqualTo(diaryEntry.getId());
        assertThat(found.getContent()).isNull();
        assertThat(found.getList()).isNull();
    }

    @Test
    @DisplayName("[save] Should Persist A Reply - When Parent Comment Is Provided")
    void shouldPersistAReplyWhenParentCommentIsProvided() {
        Comment parent = commentRepository.saveAndFlush(buildContentComment(lucas, fightClub, "Great movie!"));
        entityManager.clear();

        Comment reply = commentRepository.saveAndFlush(buildReply(marina, fightClub, parent, "Totally agree"));
        entityManager.clear();

        Comment found = commentRepository.findById(reply.getId()).orElseThrow();

        assertThat(found.getParentComment().getId()).isEqualTo(parent.getId());
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When No Target Is Provided")
    void shouldThrowDataIntegrityViolationExceptionWhenNoTargetIsProvided() {
        Comment comment = Comment.builder()
                .user(lucas)
                .text("Orphan comment")
                .containsSpoiler(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> commentRepository.saveAndFlush(comment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When More Than One Target Is Provided")
    void shouldThrowDataIntegrityViolationExceptionWhenMoreThanOneTargetIsProvided() {
        Comment comment = Comment.builder()
                .user(lucas)
                .content(fightClub)
                .list(scifi)
                .text("Two targets")
                .containsSpoiler(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> commentRepository.saveAndFlush(comment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[findByContentIdOrderByCreatedAtAsc] Should Return Only Comments Of That Content With User Already Initialized - When Multiple Targets Have Comments")
    void shouldReturnOnlyCommentsOfThatContentWhenMultipleTargetsHaveComments() {
        commentRepository.save(buildContentComment(lucas, fightClub, "First"));
        commentRepository.saveAndFlush(buildListComment(lucas, scifi, "Not this one"));
        entityManager.clear();

        Page<Comment> result = commentRepository.findByContentIdOrderByCreatedAtAsc(fightClub.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Comment::getText).containsExactly("First");
        assertThat(Hibernate.isInitialized(result.getContent().get(0).getUser())).isTrue();
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("[findByContentIdOrderByCreatedAtAsc] Should Return Comments Ordered By CreatedAt Ascending - When Multiple Comments Exist")
    void shouldReturnCommentsOrderedByCreatedAtAscendingForContent() {
        Comment older = buildContentComment(lucas, fightClub, "Older");
        older.setCreatedAt(LocalDateTime.now().minusDays(1));
        commentRepository.save(older);
        commentRepository.saveAndFlush(buildContentComment(marina, fightClub, "Newer"));
        entityManager.clear();

        Page<Comment> result = commentRepository.findByContentIdOrderByCreatedAtAsc(fightClub.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Comment::getText).containsExactly("Older", "Newer");
    }

    @Test
    @DisplayName("[findByContentIdOrderByCreatedAtAsc] Should Return Empty Page - When Content Has No Comments")
    void shouldReturnEmptyPageWhenContentHasNoComments() {
        Page<Comment> result = commentRepository.findByContentIdOrderByCreatedAtAsc(fightClub.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[findByListIdOrderByCreatedAtAsc] Should Return Only Comments Of That List With User Already Initialized - When Multiple Targets Have Comments")
    void shouldReturnOnlyCommentsOfThatListWhenMultipleTargetsHaveComments() {
        commentRepository.save(buildListComment(lucas, scifi, "Nice picks"));
        commentRepository.saveAndFlush(buildContentComment(lucas, fightClub, "Not this one"));
        entityManager.clear();

        Page<Comment> result = commentRepository.findByListIdOrderByCreatedAtAsc(scifi.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Comment::getText).containsExactly("Nice picks");
        assertThat(Hibernate.isInitialized(result.getContent().get(0).getUser())).isTrue();
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("[findByListIdOrderByCreatedAtAsc] Should Return Empty Page - When List Has No Comments")
    void shouldReturnEmptyPageWhenListHasNoComments() {
        Page<Comment> result = commentRepository.findByListIdOrderByCreatedAtAsc(scifi.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[findByDiaryEntryIdOrderByCreatedAtAsc] Should Return Only Comments Of That Diary Entry With User Already Initialized - When Multiple Targets Have Comments")
    void shouldReturnOnlyCommentsOfThatDiaryEntryWhenMultipleTargetsHaveComments() {
        commentRepository.save(buildDiaryEntryComment(lucas, diaryEntry, "Agreed"));
        commentRepository.saveAndFlush(buildContentComment(lucas, fightClub, "Not this one"));
        entityManager.clear();

        Page<Comment> result = commentRepository.findByDiaryEntryIdOrderByCreatedAtAsc(diaryEntry.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Comment::getText).containsExactly("Agreed");
        assertThat(Hibernate.isInitialized(result.getContent().get(0).getUser())).isTrue();
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("[findByDiaryEntryIdOrderByCreatedAtAsc] Should Return Empty Page - When Diary Entry Has No Comments")
    void shouldReturnEmptyPageWhenDiaryEntryHasNoComments() {
        Page<Comment> result = commentRepository.findByDiaryEntryIdOrderByCreatedAtAsc(diaryEntry.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[findByIdWithTargets] Should Return Comment With List And Its User Already Initialized - When Comment Targets A List")
    void shouldReturnCommentWithListAndItsUserAlreadyInitializedWhenCommentTargetsAList() {
        Comment saved = commentRepository.saveAndFlush(buildListComment(lucas, scifi, "Nice picks"));
        entityManager.clear();

        Comment found = commentRepository.findByIdWithTargets(saved.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(found.getList())).isTrue();
        assertThat(Hibernate.isInitialized(found.getList().getUser())).isTrue();
        assertThat(found.getList().getUser().getId()).isEqualTo(lucas.getId());
    }

    @Test
    @DisplayName("[findByIdWithTargets] Should Return Comment With Diary Entry And Its User Already Initialized - When Comment Targets A Diary Entry")
    void shouldReturnCommentWithDiaryEntryAndItsUserAlreadyInitializedWhenCommentTargetsADiaryEntry() {
        Comment saved = commentRepository.saveAndFlush(buildDiaryEntryComment(lucas, diaryEntry, "Agreed"));
        entityManager.clear();

        Comment found = commentRepository.findByIdWithTargets(saved.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(found.getDiaryEntry())).isTrue();
        assertThat(Hibernate.isInitialized(found.getDiaryEntry().getUser())).isTrue();
        assertThat(found.getDiaryEntry().getUser().getId()).isEqualTo(lucas.getId());
    }

    @Test
    @DisplayName("[findByIdWithTargets] Should Return Comment With Null List And Diary Entry - When Comment Targets Content")
    void shouldReturnCommentWithNullListAndDiaryEntryWhenCommentTargetsContent() {
        Comment saved = commentRepository.saveAndFlush(buildContentComment(lucas, fightClub, "Great movie!"));
        entityManager.clear();

        Comment found = commentRepository.findByIdWithTargets(saved.getId()).orElseThrow();

        assertThat(found.getList()).isNull();
        assertThat(found.getDiaryEntry()).isNull();
    }

    @Test
    @DisplayName("[findByIdWithTargets] Should Return Empty - When Comment Does Not Exist")
    void shouldReturnEmptyWhenCommentDoesNotExistForFindByIdWithTargets() {
        assertThat(commentRepository.findByIdWithTargets(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Comment Rows - When The Target Content Is Deleted")
    void shouldCascadeDeleteCommentRowsWhenTheTargetContentIsDeleted() {
        Comment saved = commentRepository.saveAndFlush(buildContentComment(lucas, fightClub, "Great movie!"));
        entityManager.clear();

        contentRepository.delete(contentRepository.findById(fightClub.getId()).orElseThrow());
        contentRepository.flush();

        assertThat(commentRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Comment Rows - When The Target List Is Deleted")
    void shouldCascadeDeleteCommentRowsWhenTheTargetListIsDeleted() {
        Comment saved = commentRepository.saveAndFlush(buildListComment(lucas, scifi, "Nice picks"));
        entityManager.clear();

        userListRepository.delete(userListRepository.findById(scifi.getId()).orElseThrow());
        userListRepository.flush();

        assertThat(commentRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Comment Rows - When The Target Diary Entry Is Deleted")
    void shouldCascadeDeleteCommentRowsWhenTheTargetDiaryEntryIsDeleted() {
        Comment saved = commentRepository.saveAndFlush(buildDiaryEntryComment(lucas, diaryEntry, "Agreed"));
        entityManager.clear();

        diaryEntryRepository.delete(diaryEntryRepository.findById(diaryEntry.getId()).orElseThrow());
        diaryEntryRepository.flush();

        assertThat(commentRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Comment Rows - When The Author Is Deleted")
    void shouldCascadeDeleteCommentRowsWhenTheAuthorIsDeleted() {
        Comment saved = commentRepository.saveAndFlush(buildContentComment(marina, fightClub, "Great movie!"));
        entityManager.clear();

        userRepository.delete(userRepository.findById(marina.getId()).orElseThrow());
        userRepository.flush();

        assertThat(commentRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete The Whole Reply Subtree - When The Parent Comment Is Deleted")
    void shouldCascadeDeleteTheWholeReplySubtreeWhenTheParentCommentIsDeleted() {
        Comment parent = commentRepository.saveAndFlush(buildContentComment(lucas, fightClub, "Great movie!"));
        Comment reply = commentRepository.saveAndFlush(buildReply(marina, fightClub, parent, "Totally agree"));
        entityManager.clear();

        commentRepository.delete(commentRepository.findById(parent.getId()).orElseThrow());
        commentRepository.flush();

        assertThat(commentRepository.findById(reply.getId())).isEmpty();
    }

    @Test
    @DisplayName("[incrementLikesCount] Should Increase LikesCount By One - When Called")
    void shouldIncreaseLikesCountByOneWhenIncrementLikesCountIsCalled() {
        Comment saved = commentRepository.saveAndFlush(buildContentComment(lucas, fightClub, "Great movie!"));
        entityManager.clear();

        commentRepository.incrementLikesCount(saved.getId());
        entityManager.clear();

        assertThat(commentRepository.findById(saved.getId()).orElseThrow().getLikesCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("[decrementLikesCount] Should Decrease LikesCount By One - When Greater Than Zero")
    void shouldDecreaseLikesCountByOneWhenGreaterThanZero() {
        Comment saved = commentRepository.saveAndFlush(buildContentComment(lucas, fightClub, "Great movie!"));
        commentRepository.incrementLikesCount(saved.getId());
        entityManager.clear();

        commentRepository.decrementLikesCount(saved.getId());
        entityManager.clear();

        assertThat(commentRepository.findById(saved.getId()).orElseThrow().getLikesCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("[decrementLikesCount] Should Not Go Below Zero - When Already Zero")
    void shouldNotGoBelowZeroWhenAlreadyZero() {
        Comment saved = commentRepository.saveAndFlush(buildContentComment(lucas, fightClub, "Great movie!"));
        entityManager.clear();

        commentRepository.decrementLikesCount(saved.getId());
        entityManager.clear();

        assertThat(commentRepository.findById(saved.getId()).orElseThrow().getLikesCount()).isEqualTo(0);
    }

    private Comment buildContentComment(User user, Content content, String text) {
        LocalDateTime now = LocalDateTime.now();
        return Comment.builder()
                .user(user)
                .content(content)
                .text(text)
                .containsSpoiler(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Comment buildListComment(User user, UserList list, String text) {
        LocalDateTime now = LocalDateTime.now();
        return Comment.builder()
                .user(user)
                .list(list)
                .text(text)
                .containsSpoiler(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Comment buildDiaryEntryComment(User user, DiaryEntry diaryEntry, String text) {
        LocalDateTime now = LocalDateTime.now();
        return Comment.builder()
                .user(user)
                .diaryEntry(diaryEntry)
                .text(text)
                .containsSpoiler(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Comment buildReply(User user, Content content, Comment parentComment, String text) {
        LocalDateTime now = LocalDateTime.now();
        return Comment.builder()
                .user(user)
                .content(content)
                .parentComment(parentComment)
                .text(text)
                .containsSpoiler(false)
                .createdAt(now)
                .updatedAt(now)
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

    private Content buildContent(String tmdbId, ContentType type) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .tmdbId(tmdbId)
                .type(type)
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