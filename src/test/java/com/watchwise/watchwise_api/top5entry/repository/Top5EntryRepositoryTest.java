package com.watchwise.watchwise_api.top5entry.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class Top5EntryRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private Top5EntryRepository top5EntryRepository;

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
        top5EntryRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();

        lucas = userRepository.save(buildUser("lucas", "lucas@email.com"));
        marina = userRepository.save(buildUser("marina", "marina@email.com"));
        fightClub = contentRepository.save(buildContent("550", ContentType.MOVIE));
        pulpFiction = contentRepository.save(buildContent("680", ContentType.MOVIE));
        breakingBad = contentRepository.save(buildContent("1396", ContentType.SERIES));
    }

    @Test
    @DisplayName("[findByUserIdAndTypeOrderByPositionAsc] Should Return Entries Ordered By Position - When Multiple Entries Exist")
    void shouldReturnEntriesOrderedByPositionWhenMultipleEntriesExist() {
        top5EntryRepository.save(buildEntry(lucas, fightClub, ContentType.MOVIE, 2));
        top5EntryRepository.saveAndFlush(buildEntry(lucas, pulpFiction, ContentType.MOVIE, 1));
        entityManager.clear();

        List<Top5Entry> result = top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucas.getId(), ContentType.MOVIE);

        assertThat(result).extracting(Top5Entry::getPosition).containsExactly(1, 2);
        assertThat(result).extracting(entry -> entry.getContent().getId())
                .containsExactly(pulpFiction.getId(), fightClub.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndTypeOrderByPositionAsc] Should Return Empty List - When User Has No Entries Of That Type")
    void shouldReturnEmptyListWhenUserHasNoEntriesOfThatType() {
        List<Top5Entry> result = top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucas.getId(), ContentType.MOVIE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findByUserIdAndTypeOrderByPositionAsc] Should Not Include Entries Of A Different Type - When Filtering")
    void shouldNotIncludeEntriesOfADifferentTypeWhenFiltering() {
        top5EntryRepository.save(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        top5EntryRepository.saveAndFlush(buildEntry(lucas, breakingBad, ContentType.SERIES, 1));
        entityManager.clear();

        List<Top5Entry> result = top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucas.getId(), ContentType.MOVIE);

        assertThat(result).extracting(entry -> entry.getContent().getId()).containsExactly(fightClub.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndTypeOrderByPositionAsc] Should Not Include Entries Of A Different User - When Filtering")
    void shouldNotIncludeEntriesOfADifferentUserWhenFiltering() {
        top5EntryRepository.save(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        top5EntryRepository.saveAndFlush(buildEntry(marina, pulpFiction, ContentType.MOVIE, 1));
        entityManager.clear();

        List<Top5Entry> result = top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucas.getId(), ContentType.MOVIE);

        assertThat(result).extracting(entry -> entry.getContent().getId()).containsExactly(fightClub.getId());
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Position Is Already Taken For That User And Type")
    void shouldThrowDataIntegrityViolationExceptionWhenPositionIsAlreadyTakenForThatUserAndType() {
        top5EntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        entityManager.clear();

        assertThatThrownBy(() -> top5EntryRepository.saveAndFlush(buildEntry(lucas, pulpFiction, ContentType.MOVIE, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Content Is Already In That User's Top 5 Of That Type")
    void shouldThrowDataIntegrityViolationExceptionWhenContentIsAlreadyInThatUsersTop5OfThatType() {
        top5EntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        entityManager.clear();

        assertThatThrownBy(() -> top5EntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Allow Same Position For Different Types - When Same User")
    void shouldAllowSamePositionForDifferentTypesWhenSameUser() {
        top5EntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        entityManager.clear();

        Top5Entry saved = top5EntryRepository.saveAndFlush(buildEntry(lucas, breakingBad, ContentType.SERIES, 1));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("[save] Should Allow Same Position For Different Users")
    void shouldAllowSamePositionForDifferentUsers() {
        top5EntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        entityManager.clear();

        Top5Entry saved = top5EntryRepository.saveAndFlush(buildEntry(marina, pulpFiction, ContentType.MOVIE, 1));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Position Is Below Minimum")
    void shouldThrowDataIntegrityViolationExceptionWhenPositionIsBelowMinimum() {
        assertThatThrownBy(() -> top5EntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Position Is Above Maximum")
    void shouldThrowDataIntegrityViolationExceptionWhenPositionIsAboveMaximum() {
        assertThatThrownBy(() -> top5EntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 6)))
                .isInstanceOf(DataIntegrityViolationException.class);
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

        assertThatThrownBy(() -> top5EntryRepository.saveAndFlush(buildEntry(lucas, season, ContentType.SEASON, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Top5Entry Rows - When The User Is Deleted")
    void shouldCascadeDeleteTop5EntryRowsWhenTheUserIsDeleted() {
        Top5Entry saved = top5EntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        entityManager.clear();

        userRepository.delete(userRepository.findById(lucas.getId()).orElseThrow());
        userRepository.flush();

        assertThat(top5EntryRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Top5Entry Rows - When The Content Is Deleted")
    void shouldCascadeDeleteTop5EntryRowsWhenTheContentIsDeleted() {
        Top5Entry saved = top5EntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        entityManager.clear();

        contentRepository.delete(contentRepository.findById(fightClub.getId()).orElseThrow());
        contentRepository.flush();

        assertThat(top5EntryRepository.findById(saved.getId())).isEmpty();
    }

    private Top5Entry buildEntry(User user, Content content, ContentType type, Integer position) {
        LocalDateTime now = LocalDateTime.now();
        return Top5Entry.builder()
                .user(user)
                .content(content)
                .type(type)
                .position(position)
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