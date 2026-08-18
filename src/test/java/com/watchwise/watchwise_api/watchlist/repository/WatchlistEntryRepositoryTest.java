package com.watchwise.watchwise_api.watchlist.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class WatchlistEntryRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private WatchlistEntryRepository watchlistEntryRepository;

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
        watchlistEntryRepository.deleteAll();
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
        watchlistEntryRepository.save(buildEntry(lucas, fightClub, ContentType.MOVIE, 2));
        watchlistEntryRepository.saveAndFlush(buildEntry(lucas, pulpFiction, ContentType.MOVIE, 1));
        entityManager.clear();

        List<WatchlistEntry> result = watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucas.getId(), ContentType.MOVIE);

        assertThat(result).extracting(WatchlistEntry::getPosition).containsExactly(1, 2);
        assertThat(result).extracting(entry -> entry.getContent().getId())
                .containsExactly(pulpFiction.getId(), fightClub.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndTypeOrderByPositionAsc] Should Return Empty List - When User Has No Entries Of That Type")
    void shouldReturnEmptyListWhenUserHasNoEntriesOfThatType() {
        List<WatchlistEntry> result = watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucas.getId(), ContentType.MOVIE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findByUserIdAndTypeOrderByPositionAsc] Should Not Include Entries Of A Different Type - When Filtering")
    void shouldNotIncludeEntriesOfADifferentTypeWhenFiltering() {
        watchlistEntryRepository.save(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        watchlistEntryRepository.saveAndFlush(buildEntry(lucas, breakingBad, ContentType.SERIES, 1));
        entityManager.clear();

        List<WatchlistEntry> result = watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucas.getId(), ContentType.MOVIE);

        assertThat(result).extracting(entry -> entry.getContent().getId()).containsExactly(fightClub.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndTypeOrderByPositionAsc] Should Not Include Entries Of A Different User - When Filtering")
    void shouldNotIncludeEntriesOfADifferentUserWhenFiltering() {
        watchlistEntryRepository.save(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        watchlistEntryRepository.saveAndFlush(buildEntry(marina, pulpFiction, ContentType.MOVIE, 1));
        entityManager.clear();

        List<WatchlistEntry> result = watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucas.getId(), ContentType.MOVIE);

        assertThat(result).extracting(entry -> entry.getContent().getId()).containsExactly(fightClub.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndTypeOrderByPositionAsc] Should Return Requested Page Ordered By Position - When Paginated")
    void shouldReturnRequestedPageOrderedByPositionWhenPaginated() {
        watchlistEntryRepository.save(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        watchlistEntryRepository.save(buildEntry(lucas, pulpFiction, ContentType.MOVIE, 2));
        watchlistEntryRepository.saveAndFlush(buildEntry(lucas, breakingBad, ContentType.SERIES, 1));
        entityManager.clear();

        Page<WatchlistEntry> firstPage = watchlistEntryRepository
                .findByUserIdAndTypeOrderByPositionAsc(lucas.getId(), ContentType.MOVIE, PageRequest.of(0, 1));

        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).extracting(entry -> entry.getContent().getId())
                .containsExactly(fightClub.getId());
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Position Is Already Taken For That User And Type")
    void shouldThrowDataIntegrityViolationExceptionWhenPositionIsAlreadyTakenForThatUserAndType() {
        watchlistEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        entityManager.clear();

        assertThatThrownBy(() -> watchlistEntryRepository.saveAndFlush(buildEntry(lucas, pulpFiction, ContentType.MOVIE, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[findByUserIdAndTypeAndContentId] Should Return The Entry - When It Exists")
    void shouldReturnTheEntryWhenItExists() {
        WatchlistEntry saved = watchlistEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        entityManager.clear();

        Optional<WatchlistEntry> result = watchlistEntryRepository
                .findByUserIdAndTypeAndContentId(lucas.getId(), ContentType.MOVIE, fightClub.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndTypeAndContentId] Should Return Empty - When No Entry Exists For That User And Content")
    void shouldReturnEmptyWhenNoEntryExistsForThatUserAndContent() {
        Optional<WatchlistEntry> result = watchlistEntryRepository
                .findByUserIdAndTypeAndContentId(lucas.getId(), ContentType.MOVIE, fightClub.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Content Is Already In That User's Watchlist Of That Type")
    void shouldThrowDataIntegrityViolationExceptionWhenContentIsAlreadyInThatUsersWatchlistOfThatType() {
        watchlistEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        entityManager.clear();

        assertThatThrownBy(() -> watchlistEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Allow Same Position For Different Types - When Same User")
    void shouldAllowSamePositionForDifferentTypesWhenSameUser() {
        watchlistEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        entityManager.clear();

        WatchlistEntry saved = watchlistEntryRepository.saveAndFlush(buildEntry(lucas, breakingBad, ContentType.SERIES, 1));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("[save] Should Allow Same Position For Different Users")
    void shouldAllowSamePositionForDifferentUsers() {
        watchlistEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        entityManager.clear();

        WatchlistEntry saved = watchlistEntryRepository.saveAndFlush(buildEntry(marina, pulpFiction, ContentType.MOVIE, 1));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Position Is Below Minimum")
    void shouldThrowDataIntegrityViolationExceptionWhenPositionIsBelowMinimum() {
        assertThatThrownBy(() -> watchlistEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Allow Position Beyond Five - When No Upper Bound Like Top 5")
    void shouldAllowPositionBeyondFiveWhenNoUpperBoundLikeTop5() {
        WatchlistEntry saved = watchlistEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 6));

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

        assertThatThrownBy(() -> watchlistEntryRepository.saveAndFlush(buildEntry(lucas, season, ContentType.SEASON, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete WatchlistEntry Rows - When The User Is Deleted")
    void shouldCascadeDeleteWatchlistEntryRowsWhenTheUserIsDeleted() {
        WatchlistEntry saved = watchlistEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        entityManager.clear();

        userRepository.delete(userRepository.findById(lucas.getId()).orElseThrow());
        userRepository.flush();

        assertThat(watchlistEntryRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete WatchlistEntry Rows - When The Content Is Deleted")
    void shouldCascadeDeleteWatchlistEntryRowsWhenTheContentIsDeleted() {
        WatchlistEntry saved = watchlistEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, ContentType.MOVIE, 1));
        entityManager.clear();

        contentRepository.delete(contentRepository.findById(fightClub.getId()).orElseThrow());
        contentRepository.flush();

        assertThat(watchlistEntryRepository.findById(saved.getId())).isEmpty();
    }

    private WatchlistEntry buildEntry(User user, Content content, ContentType type, Integer position) {
        LocalDateTime now = LocalDateTime.now();
        return WatchlistEntry.builder()
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