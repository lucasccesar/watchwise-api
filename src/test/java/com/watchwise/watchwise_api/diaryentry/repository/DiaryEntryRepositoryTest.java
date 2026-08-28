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
    @DisplayName("[findByUserIdWithFilters] Should Return Only Entries Watched In That Date Range With Content Already Initialized")
    void shouldReturnOnlyEntriesWatchedInThatYear() {
        DiaryEntry watchedIn2024 = buildEntry(lucas, fightClub);
        watchedIn2024.setWatchedDate(LocalDate.of(2024, 6, 1));
        diaryEntryRepository.save(watchedIn2024);
        DiaryEntry watchedIn2025 = buildEntry(lucas, pulpFiction);
        watchedIn2025.setWatchedDate(LocalDate.of(2025, 1, 1));
        diaryEntryRepository.saveAndFlush(watchedIn2025);
        entityManager.clear();

        Page<DiaryEntry> result = diaryEntryRepository.findByUserIdWithFilters(
                lucas.getId(), null, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(entry -> entry.getContent().getId())
                .containsExactly(fightClub.getId());
        assertThat(Hibernate.isInitialized(result.getContent().get(0).getContent())).isTrue();
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("[findByUserIdWithFilters] Should Exclude Entries With No Watched Date - When A Date Range Is Given")
    void shouldExcludeEntriesWithNoWatchedDate() {
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub));
        entityManager.clear();

        Page<DiaryEntry> result = diaryEntryRepository.findByUserIdWithFilters(
                lucas.getId(), null, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), null, PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[findByUserIdWithFilters] Should Filter By Content Type")
    void shouldFilterByContentType() {
        Content episode = buildEpisode("1399", 1, 1);
        contentRepository.save(episode);
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode));
        entityManager.clear();

        Page<DiaryEntry> result = diaryEntryRepository.findByUserIdWithFilters(
                lucas.getId(), ContentType.EPISODE, null, null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(entry -> entry.getContent().getType())
                .containsExactly(ContentType.EPISODE);
    }

    @Test
    @DisplayName("[findByUserIdWithFilters] Should Filter By HasReview")
    void shouldFilterByHasReview() {
        DiaryEntry withReview = buildEntry(lucas, fightClub);
        withReview.setComment("Great movie");
        diaryEntryRepository.saveAndFlush(withReview);
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, pulpFiction));
        entityManager.clear();

        Page<DiaryEntry> result = diaryEntryRepository.findByUserIdWithFilters(
                lucas.getId(), null, null, null, true, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(entry -> entry.getContent().getId())
                .containsExactly(fightClub.getId());
    }

    @Test
    @DisplayName("[findByUserIdWithFilters] Should Return All Entries - When No Filter Is Given")
    void shouldReturnAllEntriesWhenNoFilterIsGiven() {
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub));
        entityManager.clear();

        Page<DiaryEntry> result = diaryEntryRepository.findByUserIdWithFilters(
                lucas.getId(), null, null, null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
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

    @Test
    @DisplayName("[sumRuntimeMinutesByUserId] Should Sum Only Movie And Episode Entries - When Diary Has Mixed Content Types")
    void shouldSumOnlyMovieAndEpisodeEntriesWhenSummingRuntimeMinutes() {
        Content movieWithRuntime = contentRepository.save(buildContent("9001", ContentType.MOVIE, 139, null));
        Content episodeWithRuntime = contentRepository.save(buildEpisode("1399", 1, 1, 55));
        Content season = contentRepository.save(buildSeason("1399", 1));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movieWithRuntime));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episodeWithRuntime));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, season));

        long result = diaryEntryRepository.sumRuntimeMinutesByUserId(lucas.getId());

        assertThat(result).isEqualTo(194L);
    }

    @Test
    @DisplayName("[sumRuntimeMinutesByUserId] Should Treat Missing RuntimeMinutes As Zero - When Content Has No Runtime Set")
    void shouldTreatMissingRuntimeMinutesAsZeroWhenSummingRuntimeMinutes() {
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub));

        long result = diaryEntryRepository.sumRuntimeMinutesByUserId(lucas.getId());

        assertThat(result).isZero();
    }

    @Test
    @DisplayName("[sumRuntimeMinutesByUserIdAndWatchedDateBetween] Should Exclude Entries Outside The Window Or With No WatchedDate")
    void shouldExcludeEntriesOutsideTheWindowOrWithNoWatchedDateWhenSummingRuntimeMinutes() {
        Content insideWindow = contentRepository.save(buildContent("9001", ContentType.MOVIE, 100, null));
        Content outsideWindow = contentRepository.save(buildContent("9002", ContentType.MOVIE, 90, null));
        DiaryEntry insideEntry = buildEntry(lucas, insideWindow);
        insideEntry.setWatchedDate(LocalDate.of(2024, 6, 15));
        diaryEntryRepository.saveAndFlush(insideEntry);
        DiaryEntry outsideEntry = buildEntry(lucas, outsideWindow);
        outsideEntry.setWatchedDate(LocalDate.of(2024, 1, 1));
        diaryEntryRepository.saveAndFlush(outsideEntry);
        Content episode = contentRepository.save(buildEpisode("1399", 1, 1, 30));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode));

        long result = diaryEntryRepository.sumRuntimeMinutesByUserIdAndWatchedDateBetween(
                lucas.getId(), LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30));

        assertThat(result).isEqualTo(100L);
    }

    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserId] Should Group By Genre - When Movie Has Genres Of Its Own")
    void shouldGroupByGenreWhenMovieHasGenresOfItsOwn() {
        Content movie = contentRepository.save(buildContent("9001", ContentType.MOVIE, 139, List.of("Drama", "Thriller")));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countDistinctTitlesByGenreAndUserId(lucas.getId());

        assertThat(result).extracting(DiaryEntryRepository.GenreCount::getGenre).containsExactlyInAnyOrder("Drama", "Thriller");
        assertThat(result).allSatisfy(row -> assertThat(row.getCount()).isEqualTo(1L));
    }

    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserId] Should Resolve Genres From The Series Content - When Entry Is An Episode")
    void shouldResolveGenresFromTheSeriesContentWhenEntryIsAnEpisode() {
        contentRepository.save(buildContent("1399", ContentType.SERIES, null, List.of("Drama")));
        Content episode = contentRepository.save(buildEpisode("1399", 1, 1, 55));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countDistinctTitlesByGenreAndUserId(lucas.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getGenre()).isEqualTo("Drama");
        assertThat(result.get(0).getCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserId] Should Count Each Series Once - When User Watched Multiple Episodes Of The Same Series")
    void shouldCountEachSeriesOnceWhenUserWatchedMultipleEpisodesOfTheSameSeries() {
        contentRepository.save(buildContent("1399", ContentType.SERIES, null, List.of("Drama")));
        Content episode1 = contentRepository.save(buildEpisode("1399", 1, 1, 55));
        Content episode2 = contentRepository.save(buildEpisode("1399", 1, 2, 55));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode1));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode2));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countDistinctTitlesByGenreAndUserId(lucas.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserId] Should Count Each Movie Once - When User Rewatched It")
    void shouldCountEachMovieOnceWhenUserRewatchedIt() {
        Content movie = contentRepository.save(buildContent("9001", ContentType.MOVIE, 139, List.of("Drama")));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie, 2));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countDistinctTitlesByGenreAndUserId(lucas.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserId] Should Omit Content - When Genres And Series Are Both Missing")
    void shouldOmitContentWhenGenresAndSeriesAreBothMissing() {
        Content episodeWithoutSeriesContent = contentRepository.save(buildEpisode("9999", 1, 1, 40));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episodeWithoutSeriesContent));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countDistinctTitlesByGenreAndUserId(lucas.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findSeriesInProgressByUserId] Should Return Series - When User Has Watched Episodes But Not Completed It")
    void shouldReturnSeriesWhenUserHasWatchedEpisodesButNotCompletedIt() {
        Content episode = contentRepository.save(buildEpisode("1399", 1, 3));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode));

        Page<DiaryEntryRepository.SeriesInProgress> result =
                diaryEntryRepository.findSeriesInProgressByUserId(lucas.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        DiaryEntryRepository.SeriesInProgress row = result.getContent().get(0);
        assertThat(row.getSeriesTmdbId()).isEqualTo("1399");
        assertThat(row.getMaxSeasonNumber()).isEqualTo(1);
        assertThat(row.getMaxEpisodeNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("[findSeriesInProgressByUserId] Should Exclude Series - When User Already Has A SERIES Completion Entry For It")
    void shouldExcludeSeriesWhenUserAlreadyHasASeriesCompletionEntryForIt() {
        Content series = contentRepository.save(buildContent("1399", ContentType.SERIES));
        Content episode = contentRepository.save(buildEpisode("1399", 1, 3));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, series));

        Page<DiaryEntryRepository.SeriesInProgress> result =
                diaryEntryRepository.findSeriesInProgressByUserId(lucas.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[findSeriesInProgressByUserId] Should Report The Furthest Watched Season And Episode - When Multiple Episodes Are Watched")
    void shouldReportTheFurthestWatchedSeasonAndEpisodeWhenMultipleEpisodesAreWatched() {
        Content firstEpisode = contentRepository.save(buildEpisode("1399", 1, 1));
        Content secondEpisode = contentRepository.save(buildEpisode("1399", 2, 1));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, firstEpisode));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, secondEpisode));

        Page<DiaryEntryRepository.SeriesInProgress> result =
                diaryEntryRepository.findSeriesInProgressByUserId(lucas.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        DiaryEntryRepository.SeriesInProgress row = result.getContent().get(0);
        assertThat(row.getMaxSeasonNumber()).isEqualTo(2);
        assertThat(row.getMaxEpisodeNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("[findSeriesInProgressByUserId] Should Order By Last Watched Date Descending - When Multiple Series Are In Progress")
    void shouldOrderByLastWatchedDateDescendingWhenMultipleSeriesAreInProgress() {
        Content olderSeriesEpisode = contentRepository.save(buildEpisode("1399", 1, 1));
        Content newerSeriesEpisode = contentRepository.save(buildEpisode("1396", 1, 1));
        DiaryEntry olderEntry = buildEntry(lucas, olderSeriesEpisode);
        olderEntry.setWatchedDate(LocalDate.of(2024, 1, 1));
        diaryEntryRepository.saveAndFlush(olderEntry);
        DiaryEntry newerEntry = buildEntry(lucas, newerSeriesEpisode);
        newerEntry.setWatchedDate(LocalDate.of(2024, 6, 1));
        diaryEntryRepository.saveAndFlush(newerEntry);

        Page<DiaryEntryRepository.SeriesInProgress> result =
                diaryEntryRepository.findSeriesInProgressByUserId(lucas.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(DiaryEntryRepository.SeriesInProgress::getSeriesTmdbId)
                .containsExactly("1396", "1399");
    }

    @Test
    @DisplayName("[findSeriesInProgressByUserId] Should Return Empty Page - When User Has No Episode Entries")
    void shouldReturnEmptyPageWhenUserHasNoEpisodeEntries() {
        Page<DiaryEntryRepository.SeriesInProgress> result =
                diaryEntryRepository.findSeriesInProgressByUserId(lucas.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[sumRuntimeMinutesByUserIdAndContentType] Should Sum Only Entries Of The Given Content Type")
    void shouldSumOnlyEntriesOfTheGivenContentType() {
        Content movie = contentRepository.save(buildContent("9001", ContentType.MOVIE, 100, List.of("Drama")));
        Content episode = contentRepository.save(buildEpisode("1399", 1, 1, 40));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode));

        long result = diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentType(lucas.getId(), ContentType.MOVIE);

        assertThat(result).isEqualTo(100L);
    }

    @Test
    @DisplayName("[sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween] Should Sum Only Entries In Range And Of The Given Type")
    void shouldSumOnlyEntriesInRangeAndOfTheGivenType() {
        Content movie = contentRepository.save(buildContent("9001", ContentType.MOVIE, 100, List.of("Drama")));
        DiaryEntry inRange = buildEntry(lucas, movie);
        inRange.setWatchedDate(LocalDate.of(2024, 6, 1));
        diaryEntryRepository.saveAndFlush(inRange);
        DiaryEntry outOfRange = buildEntry(lucas, movie, 2);
        outOfRange.setWatchedDate(LocalDate.of(2023, 6, 1));
        diaryEntryRepository.saveAndFlush(outOfRange);

        long result = diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(
                lucas.getId(), ContentType.MOVIE, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result).isEqualTo(100L);
    }

    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserIdForMovies] Should Count Only Movie Titles")
    void shouldCountOnlyMovieTitles() {
        Content movie = contentRepository.save(buildContent("9001", ContentType.MOVIE, 139, List.of("Drama")));
        Content episode = contentRepository.save(buildEpisode("1399", 1, 1, 55));
        contentRepository.save(buildContent("1399", ContentType.SERIES, null, List.of("Drama")));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForMovies(lucas.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserIdForSeries] Should Count Each Series Once Resolving Genres From The Series Content")
    void shouldCountEachSeriesOnceResolvingGenresFromTheSeriesContentForSummary() {
        contentRepository.save(buildContent("1399", ContentType.SERIES, null, List.of("Drama")));
        Content episode1 = contentRepository.save(buildEpisode("1399", 1, 1, 55));
        Content episode2 = contentRepository.save(buildEpisode("1399", 1, 2, 55));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode1));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode2));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeries(lucas.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getGenre()).isEqualTo("Drama");
        assertThat(result.get(0).getCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[countByUserIdAndContentTypeGroupByScore] Should Group By Score Ignoring Entries Without A Score")
    void shouldGroupByScoreIgnoringEntriesWithoutAScore() {
        Content movie = contentRepository.save(buildContent("9001", ContentType.MOVIE));
        Content pulp = contentRepository.save(buildContent("9002", ContentType.MOVIE));
        DiaryEntry scored = buildEntry(lucas, movie);
        scored.setScore(8);
        diaryEntryRepository.saveAndFlush(scored);
        DiaryEntry unscored = buildEntry(lucas, pulp);
        diaryEntryRepository.saveAndFlush(unscored);

        List<DiaryEntryRepository.ScoreCount> result =
                diaryEntryRepository.countByUserIdAndContentTypeGroupByScore(lucas.getId(), ContentType.MOVIE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScore()).isEqualTo(8);
        assertThat(result.get(0).getCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[findTopByUserIdAndContentTypeOrderByCreatedAtDesc] Should Return Only Entries Of The Given Type Most Recently Created First")
    void shouldReturnOnlyEntriesOfTheGivenTypeMostRecentlyCreatedFirst() {
        Content movie = contentRepository.save(buildContent("9001", ContentType.MOVIE));
        Content series = contentRepository.save(buildContent("1399", ContentType.SERIES));
        DiaryEntry olderMovie = buildEntry(lucas, movie);
        olderMovie.setCreatedAt(LocalDateTime.now().minusDays(1));
        diaryEntryRepository.saveAndFlush(olderMovie);
        DiaryEntry newerMovie = buildEntry(lucas, movie, 2);
        diaryEntryRepository.saveAndFlush(newerMovie);
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, series));

        List<DiaryEntry> result = diaryEntryRepository.findTopByUserIdAndContentTypeOrderByCreatedAtDesc(
                lucas.getId(), ContentType.MOVIE, PageRequest.of(0, 10));

        assertThat(result).extracting(DiaryEntry::getId).containsExactly(newerMovie.getId(), olderMovie.getId());
    }

    @Test
    @DisplayName("[findTopByUserIdAndContentTypeOrderByCreatedAtDesc] Should Respect The Pageable Limit")
    void shouldRespectThePageableLimitForFindTopByUserIdAndContentType() {
        Content movie = contentRepository.save(buildContent("9001", ContentType.MOVIE));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie, 2));

        List<DiaryEntry> result = diaryEntryRepository.findTopByUserIdAndContentTypeOrderByCreatedAtDesc(
                lucas.getId(), ContentType.MOVIE, PageRequest.of(0, 1));

        assertThat(result).hasSize(1);
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

    private Content buildContent(String tmdbId, ContentType type, Integer runtimeMinutes, List<String> genres) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .tmdbId(tmdbId)
                .type(type)
                .runtimeMinutes(runtimeMinutes)
                .genres(genres)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Content buildEpisode(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber, Integer runtimeMinutes) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .runtimeMinutes(runtimeMinutes)
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