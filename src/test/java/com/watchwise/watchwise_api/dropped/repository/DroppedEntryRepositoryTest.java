package com.watchwise.watchwise_api.dropped.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
class DroppedEntryRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private DroppedEntryRepository droppedEntryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContentRepository contentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User lucas;
    private User marina;
    private Content fightClub;
    private Content pulpFiction;
    private Content breakingBad;

    @BeforeEach
    void setUp() {
        droppedEntryRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();

        lucas = userRepository.save(buildUser("lucas", "lucas@email.com"));
        marina = userRepository.save(buildUser("marina", "marina@email.com"));
        fightClub = contentRepository.save(buildContent("550", ContentType.MOVIE));
        pulpFiction = contentRepository.save(buildContent("680", ContentType.MOVIE));
        breakingBad = contentRepository.save(buildContent("1396", ContentType.SERIES));
    }

    @Test
    @DisplayName("[findByUserIdAndTypeAndContentId] Should Return The Entry - When It Exists")
    void shouldReturnTheEntryWhenItExists() {
        DroppedEntry saved = droppedEntryRepository.saveAndFlush(buildEntry(lucas, breakingBad, ContentType.SERIES, null));
        entityManager.clear();

        Optional<DroppedEntry> result = droppedEntryRepository
                .findByUserIdAndTypeAndContentId(lucas.getId(), ContentType.SERIES, breakingBad.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndTypeAndContentId] Should Return Empty - When No Entry Exists For That User And Content")
    void shouldReturnEmptyWhenNoEntryExistsForThatUserAndContent() {
        Optional<DroppedEntry> result = droppedEntryRepository
                .findByUserIdAndTypeAndContentId(lucas.getId(), ContentType.SERIES, breakingBad.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[existsByUserIdAndTypeAndContentId] Should Return True - When Entry Exists")
    void shouldReturnTrueWhenEntryExists() {
        droppedEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, null));
        entityManager.clear();

        boolean result = droppedEntryRepository.existsByUserIdAndTypeAndContentId(lucas.getId(), ContentType.MOVIE, fightClub.getId());

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("[existsByUserIdAndTypeAndContentId] Should Return False - When Entry Does Not Exist")
    void shouldReturnFalseWhenEntryDoesNotExist() {
        boolean result = droppedEntryRepository.existsByUserIdAndTypeAndContentId(lucas.getId(), ContentType.MOVIE, fightClub.getId());

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("[findByUserIdAndTypeOrderByCreatedAtDesc] Should Return Entries Ordered By CreatedAt Descending - When Multiple Entries Exist")
    void shouldReturnEntriesOrderedByCreatedAtDescendingWhenMultipleEntriesExist() {
        DroppedEntry older = droppedEntryRepository.saveAndFlush(
                buildEntryWithCreatedAt(lucas, fightClub, ContentType.MOVIE, LocalDateTime.now().minusDays(1)));
        DroppedEntry newer = droppedEntryRepository.saveAndFlush(
                buildEntryWithCreatedAt(lucas, pulpFiction, ContentType.MOVIE, LocalDateTime.now()));
        entityManager.clear();

        Page<DroppedEntry> result = droppedEntryRepository
                .findByUserIdAndTypeOrderByCreatedAtDesc(lucas.getId(), ContentType.MOVIE, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(DroppedEntry::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndTypeOrderByCreatedAtDesc] Should Not Include Entries Of A Different Type - When Filtering")
    void shouldNotIncludeEntriesOfADifferentTypeWhenFiltering() {
        droppedEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, null));
        droppedEntryRepository.saveAndFlush(buildEntry(lucas, breakingBad, ContentType.SERIES, null));
        entityManager.clear();

        Page<DroppedEntry> result = droppedEntryRepository
                .findByUserIdAndTypeOrderByCreatedAtDesc(lucas.getId(), ContentType.MOVIE, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(entry -> entry.getContent().getId()).containsExactly(fightClub.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndTypeOrderByCreatedAtDesc] Should Not Include Entries Of A Different User - When Filtering")
    void shouldNotIncludeEntriesOfADifferentUserWhenFiltering() {
        droppedEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, null));
        droppedEntryRepository.saveAndFlush(buildEntry(marina, pulpFiction, ContentType.MOVIE, null));
        entityManager.clear();

        Page<DroppedEntry> result = droppedEntryRepository
                .findByUserIdAndTypeOrderByCreatedAtDesc(lucas.getId(), ContentType.MOVIE, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(entry -> entry.getContent().getId()).containsExactly(fightClub.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndTypeOrderByCreatedAtDesc] Should Return Requested Page - When Paginated")
    void shouldReturnRequestedPageWhenPaginated() {
        droppedEntryRepository.saveAndFlush(buildEntryWithCreatedAt(lucas, fightClub, ContentType.MOVIE, LocalDateTime.now().minusDays(1)));
        droppedEntryRepository.saveAndFlush(buildEntryWithCreatedAt(lucas, pulpFiction, ContentType.MOVIE, LocalDateTime.now()));
        entityManager.clear();

        Page<DroppedEntry> firstPage = droppedEntryRepository
                .findByUserIdAndTypeOrderByCreatedAtDesc(lucas.getId(), ContentType.MOVIE, PageRequest.of(0, 1));

        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).extracting(entry -> entry.getContent().getId()).containsExactly(pulpFiction.getId());
    }

    @Test
    @DisplayName("[save] Should Persist The Comment - When Provided")
    void shouldPersistTheCommentWhenProvided() {
        DroppedEntry saved = droppedEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, "Lost interest halfway through"));
        entityManager.clear();

        DroppedEntry found = droppedEntryRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getComment()).isEqualTo("Lost interest halfway through");
    }

    @Test
    @DisplayName("[save] Should Allow A Null Comment")
    void shouldAllowANullComment() {
        DroppedEntry saved = droppedEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, null));
        entityManager.clear();

        DroppedEntry found = droppedEntryRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getComment()).isNull();
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Content Is Already Dropped By That User For That Type")
    void shouldThrowDataIntegrityViolationExceptionWhenContentIsAlreadyDroppedByThatUserForThatType() {
        droppedEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, null));
        entityManager.clear();

        assertThatThrownBy(() -> droppedEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, "again")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Allow The Same Content Dropped By Different Users")
    void shouldAllowTheSameContentDroppedByDifferentUsers() {
        droppedEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, null));
        entityManager.clear();

        DroppedEntry saved = droppedEntryRepository.saveAndFlush(buildEntry(marina, fightClub, ContentType.MOVIE, null));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Type Is Not Movie Or Series")
    void shouldThrowDataIntegrityViolationExceptionWhenTypeIsNotMovieOrSeries() {
        Content season = contentRepository.save(Content.builder()
                .seriesTmdbId("1396")
                .seasonNumber(1)
                .type(ContentType.SEASON)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        assertThatThrownBy(() -> droppedEntryRepository.saveAndFlush(buildEntry(lucas, season, ContentType.SEASON, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete DroppedEntry Rows - When The User Is Deleted")
    void shouldCascadeDeleteDroppedEntryRowsWhenTheUserIsDeleted() {
        DroppedEntry saved = droppedEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, null));
        entityManager.clear();

        userRepository.delete(userRepository.findById(lucas.getId()).orElseThrow());
        userRepository.flush();

        assertThat(droppedEntryRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete DroppedEntry Rows - When The Content Is Deleted")
    void shouldCascadeDeleteDroppedEntryRowsWhenTheContentIsDeleted() {
        DroppedEntry saved = droppedEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, null));
        entityManager.clear();

        contentRepository.delete(contentRepository.findById(fightClub.getId()).orElseThrow());
        contentRepository.flush();

        assertThat(droppedEntryRepository.findById(saved.getId())).isEmpty();
    }

    private DroppedEntry buildEntry(User user, Content content, ContentType type, String comment) {
        LocalDateTime now = LocalDateTime.now();
        return DroppedEntry.builder()
                .user(user)
                .content(content)
                .type(type)
                .comment(comment)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private DroppedEntry buildEntryWithCreatedAt(User user, Content content, ContentType type, LocalDateTime createdAt) {
        return DroppedEntry.builder()
                .user(user)
                .content(content)
                .type(type)
                .createdAt(createdAt)
                .updatedAt(createdAt)
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