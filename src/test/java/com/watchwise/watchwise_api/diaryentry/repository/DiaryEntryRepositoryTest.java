package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DiaryEntryRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private DiaryEntryRepository diaryEntryRepository;

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

    @BeforeEach
    void setUp() {
        diaryEntryRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();

        lucas = userRepository.save(buildUser("lucas", "lucas@email.com"));
        marina = userRepository.save(buildUser("marina", "marina@email.com"));
        fightClub = contentRepository.save(buildContent("550", ContentType.MOVIE));
        pulpFiction = contentRepository.save(buildContent("680", ContentType.MOVIE));
    }

    @Test
    @DisplayName("[findByUserIdOrderByCreatedAtDesc] Should Return Only Entries Of That User With Content Already Initialized - When Multiple Users Have Entries")
    void shouldReturnOnlyEntriesOfThatUserWhenMultipleUsersHaveEntries() {
        diaryEntryRepository.save(buildEntry(lucas, fightClub));
        diaryEntryRepository.saveAndFlush(buildEntry(marina, pulpFiction));
        entityManager.clear();

        Page<DiaryEntry> result = diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(lucas.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(entry -> entry.getContent().getId())
                .containsExactly(fightClub.getId());
        assertThat(Hibernate.isInitialized(result.getContent().get(0).getContent())).isTrue();
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("[findByUserIdOrderByCreatedAtDesc] Should Return Empty Page - When User Has No Entries")
    void shouldReturnEmptyPageWhenUserHasNoEntries() {
        Page<DiaryEntry> result = diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(lucas.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[findByUserIdOrderByCreatedAtDesc] Should Return Entries Most Recently Created First - When Multiple Entries Exist")
    void shouldReturnEntriesMostRecentlyCreatedFirstWhenMultipleEntriesExist() {
        LocalDateTime earlier = LocalDateTime.now().minusDays(1);
        LocalDateTime later = LocalDateTime.now();
        DiaryEntry olderEntry = buildEntry(lucas, fightClub);
        olderEntry.setCreatedAt(earlier);
        olderEntry.setUpdatedAt(earlier);
        diaryEntryRepository.save(olderEntry);
        DiaryEntry newerEntry = buildEntry(lucas, pulpFiction);
        newerEntry.setCreatedAt(later);
        newerEntry.setUpdatedAt(later);
        diaryEntryRepository.saveAndFlush(newerEntry);
        entityManager.clear();

        Page<DiaryEntry> result = diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(lucas.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(entry -> entry.getContent().getId())
                .containsExactly(pulpFiction.getId(), fightClub.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc] Should Return Only Entries Watched In That Year With Content Already Initialized")
    void shouldReturnOnlyEntriesWatchedInThatYear() {
        DiaryEntry watchedIn2024 = buildEntry(lucas, fightClub);
        watchedIn2024.setWatchedDate(LocalDate.of(2024, 6, 1));
        diaryEntryRepository.save(watchedIn2024);
        DiaryEntry watchedIn2025 = buildEntry(lucas, pulpFiction);
        watchedIn2025.setWatchedDate(LocalDate.of(2025, 1, 1));
        diaryEntryRepository.saveAndFlush(watchedIn2025);
        entityManager.clear();

        Page<DiaryEntry> result = diaryEntryRepository.findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc(
                lucas.getId(), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(entry -> entry.getContent().getId())
                .containsExactly(fightClub.getId());
        assertThat(Hibernate.isInitialized(result.getContent().get(0).getContent())).isTrue();
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("[findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc] Should Exclude Entries With No Watched Date")
    void shouldExcludeEntriesWithNoWatchedDate() {
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub));
        entityManager.clear();

        Page<DiaryEntry> result = diaryEntryRepository.findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc(
                lucas.getId(), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[findByUserIdAndContentIdAndWatchNumberGreaterThan] Should Return Only Entries Above The Threshold - When User Has Multiple Watches")
    void shouldReturnOnlyEntriesAboveTheThresholdWhenUserHasMultipleWatches() {
        DiaryEntry firstWatch = diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub));
        DiaryEntry secondWatch = diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, 2));
        entityManager.clear();

        List<DiaryEntry> result = diaryEntryRepository
                .findByUserIdAndContentIdAndWatchNumberGreaterThan(lucas.getId(), fightClub.getId(), 1);

        assertThat(result).extracting(DiaryEntry::getId).containsExactly(secondWatch.getId());
        assertThat(firstWatch.getWatchNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("[findByUserIdAndContentIdAndWatchNumberGreaterThan] Should Return Empty - When No Entry Exceeds The Threshold")
    void shouldReturnEmptyWhenNoEntryExceedsTheThreshold() {
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub));
        entityManager.clear();

        List<DiaryEntry> result = diaryEntryRepository
                .findByUserIdAndContentIdAndWatchNumberGreaterThan(lucas.getId(), fightClub.getId(), 1);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[save] Should Persist Optional Fields As Null - When Only Required Fields Are Provided")
    void shouldPersistOptionalFieldsAsNullWhenOnlyRequiredFieldsAreProvided() {
        DiaryEntry saved = diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub));
        entityManager.clear();

        DiaryEntry found = diaryEntryRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getScore()).isNull();
        assertThat(found.getComment()).isNull();
        assertThat(found.getWatchedDate()).isNull();
        assertThat(found.getWatchedInTheater()).isNull();
        assertThat(found.getCustomPosterUrl()).isNull();
        assertThat(found.getWatchNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("[save] Should Allow Multiple Entries For Same User And Content - When Rewatching")
    void shouldAllowMultipleEntriesForSameUserAndContentWhenRewatching() {
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub));
        entityManager.clear();

        DiaryEntry saved = diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub, 2));

        assertThat(saved.getId()).isNotNull();
        assertThat(diaryEntryRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Score Is Below Minimum")
    void shouldThrowDataIntegrityViolationExceptionWhenScoreIsBelowMinimum() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setScore(0);

        assertThatThrownBy(() -> diaryEntryRepository.saveAndFlush(entry))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Score Is Above Maximum")
    void shouldThrowDataIntegrityViolationExceptionWhenScoreIsAboveMaximum() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setScore(11);

        assertThatThrownBy(() -> diaryEntryRepository.saveAndFlush(entry))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete DiaryEntry Rows - When The User Is Deleted")
    void shouldCascadeDeleteDiaryEntryRowsWhenTheUserIsDeleted() {
        DiaryEntry saved = diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub));
        entityManager.clear();

        userRepository.delete(userRepository.findById(lucas.getId()).orElseThrow());
        userRepository.flush();

        assertThat(diaryEntryRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete DiaryEntry Rows - When The Content Is Deleted")
    void shouldCascadeDeleteDiaryEntryRowsWhenTheContentIsDeleted() {
        DiaryEntry saved = diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub));
        entityManager.clear();

        contentRepository.delete(contentRepository.findById(fightClub.getId()).orElseThrow());
        contentRepository.flush();

        assertThat(diaryEntryRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[incrementLikesCount] Should Increase LikesCount By One - When Called")
    void shouldIncreaseLikesCountByOneWhenIncrementLikesCountIsCalled() {
        DiaryEntry saved = diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub));
        entityManager.clear();

        diaryEntryRepository.incrementLikesCount(saved.getId());
        entityManager.clear();

        assertThat(diaryEntryRepository.findById(saved.getId()).orElseThrow().getLikesCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("[decrementLikesCount] Should Not Go Below Zero - When Already Zero")
    void shouldNotGoBelowZeroWhenAlreadyZero() {
        DiaryEntry saved = diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub));
        entityManager.clear();

        diaryEntryRepository.decrementLikesCount(saved.getId());
        entityManager.clear();

        assertThat(diaryEntryRepository.findById(saved.getId()).orElseThrow().getLikesCount()).isEqualTo(0);
    }

    private DiaryEntry buildEntry(User user, Content content) {
        return buildEntry(user, content, 1);
    }

    private DiaryEntry buildEntry(User user, Content content, int watchNumber) {
        LocalDateTime now = LocalDateTime.now();
        return DiaryEntry.builder()
                .user(user)
                .content(content)
                .watchNumber(watchNumber)
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

    private Content buildEpisode(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .type(ContentType.EPISODE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Content buildSeason(String seriesTmdbId, Integer seasonNumber) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .type(ContentType.SEASON)
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