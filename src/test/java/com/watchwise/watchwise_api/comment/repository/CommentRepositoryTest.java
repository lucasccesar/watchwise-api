package com.watchwise.watchwise_api.comment.repository;

import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
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
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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

    @Autowired
    private DroppedEntryRepository droppedEntryRepository;

    @Autowired
    private PickRepository pickRepository;

    @Autowired
    private PicksTemplateRepository picksTemplateRepository;

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
        pickRepository.deleteAll();
        droppedEntryRepository.deleteAll();
        diaryEntryRepository.deleteAll();
        picksTemplateRepository.deleteAll();
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
    @DisplayName("[countByListId] Should Return The Number Of Comments On That List")
    void shouldReturnTheNumberOfCommentsOnThatList() {
        commentRepository.save(buildListComment(lucas, scifi, "Nice picks"));
        commentRepository.saveAndFlush(buildListComment(marina, scifi, "Agreed"));
        entityManager.clear();

        assertThat(commentRepository.countByListId(scifi.getId())).isEqualTo(2L);
    }

    @Test
    @DisplayName("[countByListId] Should Return Zero - When List Has No Comments")
    void shouldReturnZeroWhenListHasNoComments() {
        assertThat(commentRepository.countByListId(scifi.getId())).isZero();
    }

    @Test
    @DisplayName("[countByListIdIn] Should Count Comments Per List - When Several Lists Are Requested")
    void shouldCountCommentsPerListWhenSeveralListsAreRequested() {
        UserList horror = userListRepository.save(buildList(lucas, "Underrated horror"));
        commentRepository.save(buildListComment(lucas, scifi, "Nice picks"));
        commentRepository.save(buildListComment(marina, scifi, "Agreed"));
        commentRepository.saveAndFlush(buildContentComment(lucas, fightClub, "Not this one"));
        entityManager.clear();

        List<CommentRepository.ListCommentCount> result = commentRepository.countByListIdIn(List.of(scifi.getId(), horror.getId()));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getListId()).isEqualTo(scifi.getId());
        assertThat(result.get(0).getCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("[countByTargetIdIn] Should Count Comments Per Diary Entry, Dropped Entry, Pick And Picks Template")
    void shouldCountCommentsForEachSocialTargetInBatches() {
        DiaryEntry secondDiaryEntry = diaryEntryRepository.save(buildDiaryEntry(marina, fightClub));
        DroppedEntry droppedEntry = droppedEntryRepository.saveAndFlush(buildDroppedEntry(lucas, fightClub));
        DroppedEntry secondDroppedEntry = droppedEntryRepository.saveAndFlush(buildDroppedEntry(marina, fightClub));
        PicksTemplate template = savePicksTemplate(lucas, "First template");
        PicksTemplate secondTemplate = savePicksTemplate(marina, "Second template");
        Pick pick = savePick(template, lucas);
        Pick secondPick = savePick(template, marina);

        commentRepository.save(buildDiaryEntryComment(lucas, diaryEntry, "Diary one"));
        commentRepository.save(buildDiaryEntryComment(marina, diaryEntry, "Diary two"));
        commentRepository.save(buildDiaryEntryComment(lucas, secondDiaryEntry, "Other diary"));
        commentRepository.save(buildDroppedEntryComment(lucas, droppedEntry, "Dropped one"));
        commentRepository.save(buildDroppedEntryComment(marina, droppedEntry, "Dropped two"));
        commentRepository.save(buildDroppedEntryComment(lucas, secondDroppedEntry, "Other dropped"));
        commentRepository.save(buildPickComment(lucas, pick, "Pick one"));
        commentRepository.save(buildPickComment(marina, pick, "Pick two"));
        commentRepository.save(buildPickComment(lucas, secondPick, "Other pick"));
        commentRepository.save(buildPicksTemplateComment(lucas, template, "Template one"));
        commentRepository.save(buildPicksTemplateComment(marina, template, "Template two"));
        commentRepository.saveAndFlush(buildPicksTemplateComment(lucas, secondTemplate, "Other template"));
        entityManager.clear();

        List<CommentRepository.DiaryCommentCount> diaryCounts = commentRepository
                .countByDiaryEntryIdIn(List.of(diaryEntry.getId(), secondDiaryEntry.getId()));
        List<CommentRepository.DroppedCommentCount> droppedCounts = commentRepository
                .countByDroppedEntryIdIn(List.of(droppedEntry.getId(), secondDroppedEntry.getId()));
        List<CommentRepository.PickCommentCount> pickCounts = commentRepository
                .countByPickIdIn(List.of(pick.getId(), secondPick.getId()));
        List<CommentRepository.TemplateCommentCount> templateCounts = commentRepository
                .countByPicksTemplateIdIn(List.of(template.getId(), secondTemplate.getId()));

        assertThat(diaryCounts).extracting(count -> tuple(count.getDiaryEntryId(), count.getCount()))
                .containsExactlyInAnyOrder(
                        tuple(diaryEntry.getId(), 2L),
                        tuple(secondDiaryEntry.getId(), 1L));
        assertThat(droppedCounts).extracting(count -> tuple(count.getDroppedEntryId(), count.getCount()))
                .containsExactlyInAnyOrder(
                        tuple(droppedEntry.getId(), 2L),
                        tuple(secondDroppedEntry.getId(), 1L));
        assertThat(pickCounts).extracting(count -> tuple(count.getPickId(), count.getCount()))
                .containsExactlyInAnyOrder(
                        tuple(pick.getId(), 2L),
                        tuple(secondPick.getId(), 1L));
        assertThat(templateCounts).extracting(count -> tuple(count.getTemplateId(), count.getCount()))
                .containsExactlyInAnyOrder(
                        tuple(template.getId(), 2L),
                        tuple(secondTemplate.getId(), 1L));
    }

    @Test
    @DisplayName("[findRecentByDiaryEntryIdIn] Should Return At Most Three Newest Comments Per Diary Entry")
    void shouldReturnThreeRecentCommentsPerDiaryEntryInNewestFirstOrder() {
        DiaryEntry secondDiaryEntry = diaryEntryRepository.saveAndFlush(buildDiaryEntry(marina, fightClub));
        LocalDateTime olderAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime tieAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        LocalDateTime newestAt = LocalDateTime.of(2026, 1, 1, 14, 0);
        savePreviewComment(buildDiaryEntryComment(lucas, diaryEntry, "Older", olderAt, commentId(1)));
        Comment tiedLowId = savePreviewComment(buildDiaryEntryComment(lucas, diaryEntry, "Tie low", tieAt, commentId(2)));
        Comment tiedHighId = savePreviewComment(buildDiaryEntryComment(marina, diaryEntry, "Tie high", tieAt, commentId(3)));
        Comment newest = savePreviewComment(buildDiaryEntryComment(marina, diaryEntry, "Newest", newestAt, commentId(4)));
        Comment otherTarget = savePreviewComment(buildDiaryEntryComment(marina, secondDiaryEntry, "Other diary", newestAt, commentId(5)));
        entityManager.clear();

        List<Comment> result = commentRepository.findRecentByDiaryEntryIdIn(List.of(diaryEntry.getId(), secondDiaryEntry.getId()));

        assertThat(result).hasSize(4);
        assertPreviewTarget(result, diaryEntry.getId(), comment -> comment.getDiaryEntry().getId(), newest, tiedHighId, tiedLowId);
        assertPreviewTarget(result, secondDiaryEntry.getId(), comment -> comment.getDiaryEntry().getId(), otherTarget);
        assertPreviewUserInitialized(result);
    }

    @Test
    @DisplayName("[findRecentByDroppedEntryIdIn] Should Return At Most Three Newest Comments Per Dropped Entry")
    void shouldReturnThreeRecentCommentsPerDroppedEntryInNewestFirstOrder() {
        DroppedEntry droppedEntry = droppedEntryRepository.saveAndFlush(buildDroppedEntry(lucas, fightClub));
        DroppedEntry secondDroppedEntry = droppedEntryRepository.saveAndFlush(buildDroppedEntry(marina, fightClub));
        LocalDateTime olderAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime tieAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        LocalDateTime newestAt = LocalDateTime.of(2026, 1, 1, 14, 0);
        savePreviewComment(buildDroppedEntryComment(lucas, droppedEntry, "Older", olderAt, commentId(1)));
        Comment tiedLowId = savePreviewComment(buildDroppedEntryComment(lucas, droppedEntry, "Tie low", tieAt, commentId(2)));
        Comment tiedHighId = savePreviewComment(buildDroppedEntryComment(marina, droppedEntry, "Tie high", tieAt, commentId(3)));
        Comment newest = savePreviewComment(buildDroppedEntryComment(marina, droppedEntry, "Newest", newestAt, commentId(4)));
        Comment otherTarget = savePreviewComment(buildDroppedEntryComment(marina, secondDroppedEntry, "Other dropped", newestAt, commentId(5)));
        entityManager.clear();

        List<Comment> result = commentRepository.findRecentByDroppedEntryIdIn(List.of(droppedEntry.getId(), secondDroppedEntry.getId()));

        assertThat(result).hasSize(4);
        assertPreviewTarget(result, droppedEntry.getId(), comment -> comment.getDroppedEntry().getId(), newest, tiedHighId, tiedLowId);
        assertPreviewTarget(result, secondDroppedEntry.getId(), comment -> comment.getDroppedEntry().getId(), otherTarget);
        assertPreviewUserInitialized(result);
    }

    @Test
    @DisplayName("[findRecentByPickIdIn] Should Return At Most Three Newest Comments Per Pick")
    void shouldReturnThreeRecentCommentsPerPickInNewestFirstOrder() {
        PicksTemplate template = savePicksTemplate(lucas, "Pick template");
        Pick pick = savePick(template, lucas);
        Pick secondPick = savePick(template, marina);
        LocalDateTime olderAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime tieAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        LocalDateTime newestAt = LocalDateTime.of(2026, 1, 1, 14, 0);
        savePreviewComment(buildPickComment(lucas, pick, "Older", olderAt, commentId(1)));
        Comment tiedLowId = savePreviewComment(buildPickComment(lucas, pick, "Tie low", tieAt, commentId(2)));
        Comment tiedHighId = savePreviewComment(buildPickComment(marina, pick, "Tie high", tieAt, commentId(3)));
        Comment newest = savePreviewComment(buildPickComment(marina, pick, "Newest", newestAt, commentId(4)));
        Comment otherTarget = savePreviewComment(buildPickComment(marina, secondPick, "Other pick", newestAt, commentId(5)));
        entityManager.clear();

        List<Comment> result = commentRepository.findRecentByPickIdIn(List.of(pick.getId(), secondPick.getId()));

        assertThat(result).hasSize(4);
        assertPreviewTarget(result, pick.getId(), comment -> comment.getPick().getId(), newest, tiedHighId, tiedLowId);
        assertPreviewTarget(result, secondPick.getId(), comment -> comment.getPick().getId(), otherTarget);
        assertPreviewUserInitialized(result);
    }

    @Test
    @DisplayName("[findRecentByPicksTemplateIdIn] Should Return At Most Three Newest Comments Per Picks Template")
    void shouldReturnThreeRecentCommentsPerPicksTemplateInNewestFirstOrder() {
        PicksTemplate template = savePicksTemplate(lucas, "First template");
        PicksTemplate secondTemplate = savePicksTemplate(marina, "Second template");
        LocalDateTime olderAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime tieAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        LocalDateTime newestAt = LocalDateTime.of(2026, 1, 1, 14, 0);
        savePreviewComment(buildPicksTemplateComment(lucas, template, "Older", olderAt, commentId(1)));
        Comment tiedLowId = savePreviewComment(buildPicksTemplateComment(lucas, template, "Tie low", tieAt, commentId(2)));
        Comment tiedHighId = savePreviewComment(buildPicksTemplateComment(marina, template, "Tie high", tieAt, commentId(3)));
        Comment newest = savePreviewComment(buildPicksTemplateComment(marina, template, "Newest", newestAt, commentId(4)));
        Comment otherTarget = savePreviewComment(buildPicksTemplateComment(marina, secondTemplate, "Other template", newestAt, commentId(5)));
        entityManager.clear();

        List<Comment> result = commentRepository.findRecentByPicksTemplateIdIn(List.of(template.getId(), secondTemplate.getId()));

        assertThat(result).hasSize(4);
        assertPreviewTarget(result, template.getId(), comment -> comment.getPicksTemplate().getId(), newest, tiedHighId, tiedLowId);
        assertPreviewTarget(result, secondTemplate.getId(), comment -> comment.getPicksTemplate().getId(), otherTarget);
        assertPreviewUserInitialized(result);
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

    private void assertPreviewTarget(List<Comment> comments, UUID targetId, Function<Comment, UUID> targetIdExtractor,
                                     Comment... expectedComments) {
        List<Comment> targetComments = comments.stream()
                .filter(comment -> targetId.equals(targetIdExtractor.apply(comment)))
                .toList();

        assertThat(targetComments).extracting(Comment::getId)
                .containsExactly(java.util.Arrays.stream(expectedComments).map(Comment::getId).toArray(UUID[]::new));
    }

    private void assertPreviewUserInitialized(List<Comment> comments) {
        assertThat(comments).allSatisfy(comment -> assertThat(Hibernate.isInitialized(comment.getUser())).isTrue());
    }

    private Comment savePreviewComment(Comment comment) {
        String targetColumn;
        UUID targetId;

        if (comment.getDiaryEntry() != null) {
            targetColumn = "diary_entry_id";
            targetId = comment.getDiaryEntry().getId();
        } else if (comment.getDroppedEntry() != null) {
            targetColumn = "dropped_entry_id";
            targetId = comment.getDroppedEntry().getId();
        } else if (comment.getPick() != null) {
            targetColumn = "pick_id";
            targetId = comment.getPick().getId();
        } else {
            targetColumn = "picks_template_id";
            targetId = comment.getPicksTemplate().getId();
        }

        entityManager.createNativeQuery("""
                INSERT INTO comments (id, user_id, %s, text, contains_spoiler, created_at, updated_at, likes_count)
                VALUES (:id, :userId, :targetId, :text, :containsSpoiler, :createdAt, :updatedAt, :likesCount)
                """.formatted(targetColumn))
                .setParameter("id", comment.getId())
                .setParameter("userId", comment.getUser().getId())
                .setParameter("targetId", targetId)
                .setParameter("text", comment.getText())
                .setParameter("containsSpoiler", comment.getContainsSpoiler())
                .setParameter("createdAt", comment.getCreatedAt())
                .setParameter("updatedAt", comment.getUpdatedAt())
                .setParameter("likesCount", comment.getLikesCount())
                .executeUpdate();
        return comment;
    }

    private UUID commentId(long value) {
        return new UUID(0L, value);
    }

    private DroppedEntry buildDroppedEntry(User user, Content content) {
        LocalDateTime now = LocalDateTime.now();
        return DroppedEntry.builder()
                .user(user)
                .content(content)
                .type(ContentType.MOVIE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private PicksTemplate savePicksTemplate(User creator, String name) {
        LocalDateTime now = LocalDateTime.now();
        return picksTemplateRepository.saveAndFlush(PicksTemplate.builder()
                .creator(creator)
                .origin(PickOrigin.COMMUNITY)
                .name(name)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private Pick savePick(PicksTemplate template, User user) {
        LocalDateTime now = LocalDateTime.now();
        return pickRepository.saveAndFlush(Pick.builder()
                .picksTemplate(template)
                .user(user)
                .visibility(PickVisibility.PUBLIC)
                .createdAt(now)
                .updatedAt(now)
                .build());
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
        return buildDiaryEntryComment(user, diaryEntry, text, now, null);
    }

    private Comment buildDiaryEntryComment(User user, DiaryEntry diaryEntry, String text,
                                           LocalDateTime createdAt, UUID id) {
        return Comment.builder()
                .id(id)
                .user(user)
                .diaryEntry(diaryEntry)
                .text(text)
                .containsSpoiler(false)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private Comment buildDroppedEntryComment(User user, DroppedEntry droppedEntry, String text) {
        LocalDateTime now = LocalDateTime.now();
        return buildDroppedEntryComment(user, droppedEntry, text, now, null);
    }

    private Comment buildDroppedEntryComment(User user, DroppedEntry droppedEntry, String text,
                                             LocalDateTime createdAt, UUID id) {
        return Comment.builder()
                .id(id)
                .user(user)
                .droppedEntry(droppedEntry)
                .text(text)
                .containsSpoiler(false)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private Comment buildPickComment(User user, Pick pick, String text) {
        LocalDateTime now = LocalDateTime.now();
        return buildPickComment(user, pick, text, now, null);
    }

    private Comment buildPickComment(User user, Pick pick, String text, LocalDateTime createdAt, UUID id) {
        return Comment.builder()
                .id(id)
                .user(user)
                .pick(pick)
                .text(text)
                .containsSpoiler(false)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private Comment buildPicksTemplateComment(User user, PicksTemplate template, String text) {
        LocalDateTime now = LocalDateTime.now();
        return buildPicksTemplateComment(user, template, text, now, null);
    }

    private Comment buildPicksTemplateComment(User user, PicksTemplate template, String text,
                                              LocalDateTime createdAt, UUID id) {
        return Comment.builder()
                .id(id)
                .user(user)
                .picksTemplate(template)
                .text(text)
                .containsSpoiler(false)
                .createdAt(createdAt)
                .updatedAt(createdAt)
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
