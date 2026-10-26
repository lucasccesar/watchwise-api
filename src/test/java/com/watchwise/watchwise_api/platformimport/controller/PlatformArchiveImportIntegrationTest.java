package com.watchwise.watchwise_api.platformimport.controller;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.platformimport.dto.ImportCommitDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportJobDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportSelectionDTO;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportModeKey;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.AliasQuery;
import com.watchwise.watchwise_api.platformimport.model.AliasTrust;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportJobService;
import com.watchwise.watchwise_api.platformimport.service.ImportModeRegistry;
import com.watchwise.watchwise_api.platformimport.service.ImportPreviewService;
import com.watchwise.watchwise_api.platformimport.service.ExternalContentAliasService;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportWorker;
import com.watchwise.watchwise_api.platformimport.storage.ImportFileStore;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "app.import.worker.enabled=false",
        "app.import.cleanup.enabled=false"
})
@Testcontainers
class PlatformArchiveImportIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String STORAGE = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
            "watchwise-platform-archive-tests-" + UUID.randomUUID()).toAbsolutePath().toString();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.import.storage-directory", () -> STORAGE);
        registry.add("app.import.staging-directory", () -> STORAGE + "-staging");
    }

    @Autowired private ImportJobService jobService;
    @Autowired private ImportPreviewService previewService;
    @Autowired private ImportModeRegistry modeRegistry;
    @Autowired private ImportWorker worker;
    @Autowired private ImportJobRepository jobs;
    @Autowired private ImportItemRepository items;
    @Autowired private ImportFileStore fileStore;
    @Autowired private UserRepository users;
    @Autowired private ContentRepository contents;
    @Autowired private UserListRepository lists;
    @Autowired private WatchlistEntryRepository watchlist;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ExternalContentAliasService aliasService;
    @MockitoBean private TmdbClient tmdbClient;
    @MockitoBean private CompletionScopeLoader completionScopeLoader;

    @BeforeEach
    void clearImports() throws Exception {
        for (ImportJob job : jobs.findAll()) fileStore.delete(job.getStorageKey());
        jdbc.execute("TRUNCATE import_items, external_import_links, external_content_aliases, imported_watch_assertions, import_deferred_records, import_file_cleanup_tasks, import_jobs CASCADE");
        clearInvocations(tmdbClient);
    }

    @Test
    void importsOnlySelectedTraktListWithoutSearchingItsMetadataTitle() throws Exception {
        var trakt = new ImportModeKey(ImportInputKind.PLATFORM_ARCHIVE, ImportSourceFormat.TRAKT_ZIP);
        var letterboxd = new ImportModeKey(ImportInputKind.PLATFORM_ARCHIVE, ImportSourceFormat.LETTERBOXD_ZIP);
        assertThat(modeRegistry.require(trakt).mode()).isEqualTo(trakt);
        assertThat(modeRegistry.require(letterboxd).mode()).isEqualTo(letterboxd);

        User owner = createUser();
        seedMovie("55101");
        byte[] archive = zip(Map.of(
                "export/lists-lists.json", "[{\"name\":\"Selected favorites\",\"description\":\"Chosen list\",\"item_count\":1,\"ids\":{\"trakt\":42}},{\"name\":\"Do not import\",\"item_count\":0,\"ids\":{\"trakt\":43}}]",
                "export/lists-list-42-selected-favorites.json", "[{\"id\":42001,\"rank\":1,\"listed_at\":\"2024-01-02T03:04:05Z\",\"type\":\"movie\",\"movie\":{\"title\":\"A movie\",\"year\":2020,\"ids\":{\"tmdb\":55101}}}]"));
        ImportJobDTO job = createAndInspect(owner, archive, ImportSourceFormat.TRAKT_ZIP, ImportPlatform.TRAKT,
                null);

        assertThat(job.inventory().lists()).hasSize(2);
        assertThat(job.inventory().categories()).anySatisfy(category -> {
            assertThat(category.kind()).isEqualTo(ImportRecordKind.WATCHLIST_ITEM);
            assertThat(category.count()).isZero();
        });

        var metadata = objectMapper.createObjectNode().put("sourceAccount", "account-lucas");
        job = jobService.select(owner.getId(), job.id(), new ImportSelectionDTO(job.previewVersion(),
                false, false, List.of("42"), metadata));
        assertThat(jobs.findById(job.id()).orElseThrow().getSourceNamespace())
                .isEqualTo("TRAKT:ACCOUNT:account-lucas");
        driveUntil(job.id(), ImportJobState.READY);

        ImportItem metadataItem = items.findFirstByJobIdAndPlanGenerationAndNormalizedKindAndSourceId(
                job.id(), 1, ImportRecordKind.LIST_METADATA, "42").orElseThrow();
        assertThat(metadataItem.getOutcome()).isEqualTo("RESOLVED");
        assertThat(metadataItem.getPayloadJson()).contains("sourceItemCount").doesNotContain("sourceQuery");
        List<ImportItem> stagedMembers = items.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                job.id(), 1, "42", ImportRecordKind.LIST_ITEM);
        assertThat(stagedMembers).singleElement().satisfies(member -> {
            assertThat(member.getParentSourceId()).isEqualTo("42");
            assertThat(member.getSourceId()).isEqualTo("42001");
            assertThat(member.getOutcome()).isEqualTo("MATCHED");
            assertThat(member.getPayloadJson()).contains("2024-01-02");
        });

        job = jobService.get(owner.getId(), job.id());
        jobService.commit(owner.getId(), job.id(), new ImportCommitDTO(job.previewVersion(), job.previewDigest()));
        driveUntil(job.id(), ImportJobState.COMPLETED);

        List<UserList> created = lists.findByUserId(owner.getId());
        assertThat(created).singleElement().satisfies(list -> {
            assertThat(list.getName()).isEqualTo("Selected favorites");
            assertThat(list.getVisibility()).isEqualTo(UserListVisibility.PUBLIC);
        });
        assertThat(jdbc.queryForObject("select count(*) from user_list_items where user_list_id = ?",
                Long.class, created.getFirst().getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from external_import_links where user_id = ? and target_type = 'USER_LIST'",
                Long.class, owner.getId())).isEqualTo(1L);
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void importsSelectedLetterboxdListFromZipUsingStoredAliasWithoutTmdbNetwork() throws Exception {
        User owner = createUser();
        String movieId = "55110";
        seedMovie(movieId);
        aliasService.remember(owner.getId(), new AliasQuery("Primer", 2004, ContentType.MOVIE),
                new ImportTarget.ContentTarget(MediaReference.movie(movieId)), AliasTrust.USER_CONFIRMED);

        String listCsv = "7\n"
                + "Name,Description,Tags,URL\n"
                + "Sci-fi discoveries,Personal selection,science fiction,"
                + "https://letterboxd.com/lucas/list/sci-fi-discoveries/\n"
                + "Date,Name,Year,Letterboxd URI,Rating,Watched Date\n"
                + ",Primer,2004,https://letterboxd.com/film/primer/,4.5,\n";
        ImportJobDTO job = createAndInspect(owner, zip(Map.of("data/list-v7.csv", listCsv)),
                ImportSourceFormat.LETTERBOXD_ZIP, ImportPlatform.LETTERBOXD, null);

        assertThat(job.inventory().lists()).singleElement().satisfies(list -> {
            assertThat(list.name()).isEqualTo("Sci-fi discoveries");
            assertThat(list.itemCount()).isEqualTo(1);
        });
        String listSourceId = job.inventory().lists().getFirst().sourceId();
        var metadata = objectMapper.createObjectNode().put("sourceAccount", "letterboxd-lucas");
        job = jobService.select(owner.getId(), job.id(), new ImportSelectionDTO(job.previewVersion(),
                false, false, List.of(listSourceId), metadata));
        assertThat(jobs.findById(job.id()).orElseThrow().getSourceNamespace())
                .isEqualTo("LETTERBOXD:ACCOUNT:letterboxd-lucas");
        driveUntil(job.id(), ImportJobState.READY);

        ImportItem member = items.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                job.id(), 1, listSourceId, ImportRecordKind.LIST_ITEM).getFirst();
        assertThat(member.getOutcome()).isEqualTo("MATCHED");
        job = jobService.get(owner.getId(), job.id());
        jobService.commit(owner.getId(), job.id(), new ImportCommitDTO(job.previewVersion(), job.previewDigest()));
        driveUntil(job.id(), ImportJobState.COMPLETED);

        List<UserList> created = lists.findByUserId(owner.getId());
        assertThat(created).singleElement().satisfies(list -> {
            assertThat(list.getName()).isEqualTo("Sci-fi discoveries");
            assertThat(list.getDescription()).isEqualTo("Personal selection");
            assertThat(list.getVisibility()).isEqualTo(UserListVisibility.PUBLIC);
            assertThat(jdbc.queryForObject("select count(*) from user_list_items item "
                            + "join contents content on content.id = item.content_id "
                            + "where item.user_list_id = ? and content.type = 'MOVIE' and content.tmdb_id = ?",
                    Long.class, list.getId(), movieId)).isEqualTo(1L);
        });
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void importsLetterboxdHistoryFromZipWithFullReviewExportHeader() throws Exception {
        User owner = createUser();
        String movieId = "55111";
        seedMovie(movieId);
        aliasService.remember(owner.getId(), new AliasQuery("Primer", 2004, ContentType.MOVIE),
                new ImportTarget.ContentTarget(MediaReference.movie(movieId)), AliasTrust.USER_CONFIRMED);

        byte[] archive = zip(Map.of(
                "data/diary.csv", "Date,Name,Year,Letterboxd URI,Rating,Rewatch,Tags,Watched Date\n"
                        + "2025-06-01,Primer,2004,https://letterboxd.com/film/primer/,3.5,false,cinema,2025-05-30\n",
                "data/reviews.csv", "Date,Name,Year,Letterboxd URI,Rating,Rewatch,Review,Tags,Watched Date\n"
                        + "2025-06-02,Primer,2004,https://letterboxd.com/film/primer/,4.5,true,"
                        + "A thoughtful review,cinema,2025-05-30\n",
                "data/watched.csv", "Date,Name,Year,Letterboxd URI\n"
                        + "2025-06-01,Primer,2004,https://letterboxd.com/film/primer/\n",
                "data/ratings.csv", "Date,Name,Year,Letterboxd URI,Rating\n"
                        + "2025-06-03,Primer,2004,https://letterboxd.com/film/primer/,5\n"));
        ImportJobDTO job = createAndInspect(owner, archive,
                ImportSourceFormat.LETTERBOXD_ZIP, ImportPlatform.LETTERBOXD, null);
        job = jobService.select(owner.getId(), job.id(), new ImportSelectionDTO(job.previewVersion(),
                true, false, List.of(), objectMapper.createObjectNode().put("sourceAccount", "letterboxd-lucas")));
        driveUntil(job.id(), ImportJobState.READY);

        job = jobService.get(owner.getId(), job.id());
        jobService.commit(owner.getId(), job.id(), new ImportCommitDTO(job.previewVersion(), job.previewDigest()));
        driveUntil(job.id(), ImportJobState.COMPLETED);

        assertThat(jdbc.queryForObject("select count(*) from diary_entries diary join contents content "
                        + "on content.id = diary.content_id where diary.user_id = ? and content.type = 'MOVIE' "
                        + "and content.tmdb_id = ?", Long.class, owner.getId(), movieId)).isEqualTo(1L);
        var imported = jdbc.queryForMap("select diary.watched_date, diary.score, diary.comment "
                        + "from diary_entries diary join contents content on content.id = diary.content_id "
                        + "where diary.user_id = ? and content.type = 'MOVIE' and content.tmdb_id = ?",
                owner.getId(), movieId);
        assertThat(imported.get("watched_date").toString()).isEqualTo("2025-05-30");
        assertThat(imported.get("score")).isEqualTo(10);
        assertThat(imported.get("comment")).isEqualTo("A thoughtful review");
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void importsAmbiguousHistoryAfterUserChoiceAndAddsOnlyNewEventsOnReimport() throws Exception {
        User owner = createUser();
        seedMovie("55102");
        when(tmdbClient.searchMovies("The Example", "en-US", 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(
                        new TmdbMovieSearchResult("55101", "The Example", null, "2001-01-01"),
                        new TmdbMovieSearchResult("55102", "The Example", null, "2001-12-31")), 1, 2)));

        ImportJobDTO firstJob = createAndInspect(owner,
                zip(Map.of("export/watched-history-1.json", watchedMovieEvent("9001", "2024-01-02T03:04:05Z"))),
                ImportSourceFormat.TRAKT_ZIP, ImportPlatform.TRAKT, "account-lucas");
        firstJob = jobService.select(owner.getId(), firstJob.id(), new ImportSelectionDTO(firstJob.previewVersion(),
                true, false, List.of(), objectMapper.createObjectNode().put("sourceAccount", "account-lucas")));
        driveUntilWaitingForResolution(firstJob.id());

        ImportItem ambiguous = items.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                        firstJob.id(), 1, PageRequest.of(0, 20)).getContent().stream()
                .filter(item -> item.getNormalizedKind() == ImportRecordKind.DIARY_EVENT).findFirst().orElseThrow();
        assertThat(ambiguous.getOutcome()).isEqualTo("AMBIGUOUS");
        assertThat(objectMapper.readTree(ambiguous.getResolvedTargetJson()).path("candidates").size()).isEqualTo(2);
        previewService.resolve(owner.getId(), firstJob.id(), List.of(new ImportResolutionOperation.ResolveTarget(
                ambiguous.getId(), new ImportTarget.ContentTarget(MediaReference.movie("55102")))),
                firstJob.previewVersion());
        driveUntil(firstJob.id(), ImportJobState.READY);
        firstJob = jobService.get(owner.getId(), firstJob.id());
        jobService.commit(owner.getId(), firstJob.id(), new ImportCommitDTO(firstJob.previewVersion(), firstJob.previewDigest()));
        driveUntil(firstJob.id(), ImportJobState.COMPLETED);

        String changedOriginal = watchedMovieEvent("9001", "2025-01-02T03:04:05Z");
        String newEvent = watchedMovieEvent("9002", "2024-01-03T03:04:05Z");
        byte[] updatedArchive = zip(Map.of("export/watched-history-1.json", "["
                + changedOriginal.substring(1, changedOriginal.length() - 1) + ","
                + newEvent.substring(1, newEvent.length() - 1) + "]"));
        ImportJobDTO secondJob = createAndInspect(owner, updatedArchive, ImportSourceFormat.TRAKT_ZIP,
                ImportPlatform.TRAKT, "account-lucas");
        secondJob = jobService.select(owner.getId(), secondJob.id(), new ImportSelectionDTO(secondJob.previewVersion(),
                true, false, List.of(), objectMapper.createObjectNode().put("sourceAccount", "account-lucas")));
        driveUntil(secondJob.id(), ImportJobState.READY);
        secondJob = jobService.get(owner.getId(), secondJob.id());
        jobService.commit(owner.getId(), secondJob.id(), new ImportCommitDTO(secondJob.previewVersion(), secondJob.previewDigest()));
        driveUntil(secondJob.id(), ImportJobState.COMPLETED);

        assertThat(items.findFirstByJobIdAndPlanGenerationAndNormalizedKindAndSourceId(
                secondJob.id(), 1, ImportRecordKind.DIARY_EVENT, "9001").orElseThrow().getOutcome())
                .isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries diary JOIN contents content "
                        + "ON content.id=diary.content_id WHERE diary.user_id=? AND content.type='MOVIE' "
                        + "AND content.tmdb_id='55102'", Long.class, owner.getId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT diary.watched_date FROM diary_entries diary JOIN contents content "
                        + "ON content.id=diary.content_id WHERE diary.user_id=? AND content.type='MOVIE' "
                        + "AND content.tmdb_id='55102' AND diary.watch_number=1", LocalDate.class, owner.getId()))
                .isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(items.findFirstByJobIdAndPlanGenerationAndNormalizedKindAndSourceId(
                secondJob.id(), 1, ImportRecordKind.DIARY_EVENT, "9002").orElseThrow().getOutcome())
                .isEqualTo("APPLIED");
        verify(tmdbClient, times(1)).searchMovies("The Example", "en-US", 1);
        verifyNoMoreInteractions(tmdbClient);
    }

    @Test
    void watchlistOnlyUsesEpisodeEvidenceWithoutCreatingDiaryAssertionsOrSourceLinks() throws Exception {
        User owner = createUser();
        seedSeries("66101");
        String event = "[{\"id\":901,\"type\":\"episode\",\"watched_at\":\"2024-01-02T03:04:05Z\","
                + "\"episode\":{\"title\":\"Pilot\",\"season\":1,\"number\":1,\"ids\":{\"trakt\":9901}},"
                + "\"show\":{\"title\":\"Watched series\",\"year\":2020,\"ids\":{\"trakt\":66101,\"tmdb\":66101}}}]";
        String watchlistJson = "[{\"rank\":1,\"type\":\"show\",\"show\":{\"title\":\"Watched series\","
                + "\"year\":2020,\"ids\":{\"trakt\":66101,\"tmdb\":66101}}}]";
        byte[] archive = zip(Map.of("export/watched-history-1.json", event,
                "export/lists-watchlist.json", watchlistJson));
        ImportJobDTO job = createAndInspect(owner, archive, ImportSourceFormat.TRAKT_ZIP, ImportPlatform.TRAKT,
                "account-lucas");
        job = jobService.select(owner.getId(), job.id(), new ImportSelectionDTO(job.previewVersion(),
                false, true, List.of(), objectMapper.createObjectNode().put("sourceAccount", "account-lucas")));
        driveUntil(job.id(), ImportJobState.READY);

        ImportItem evidence = items.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                job.id(), 1, PageRequest.of(0, 20)).getContent().stream()
                .filter(item -> item.getNormalizedKind() == ImportRecordKind.WATCHED_MARKER).findFirst().orElseThrow();
        assertThat(evidence.getOutcome()).isEqualTo("AUXILIARY_EVIDENCE");
        assertThat(evidence.getResolvedContentKey()).isEqualTo("CONTENT|EPISODE|66101|1|1");

        job = jobService.get(owner.getId(), job.id());
        jobService.commit(owner.getId(), job.id(), new ImportCommitDTO(job.previewVersion(), job.previewDigest()));
        driveUntil(job.id(), ImportJobState.COMPLETED);

        assertThat(watchlist.findByUserIdOrderByPositionAsc(owner.getId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from diary_entries where user_id = ?",
                Long.class, owner.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from imported_watch_assertions where user_id = ?",
                Long.class, owner.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from external_import_links where user_id = ? and object_kind = 'auxiliary_watch_marker'",
                Long.class, owner.getId())).isZero();
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void confirmedAllReleasedScopeCreatesOnlyKnownUndatedEpisodeFacts() throws Exception {
        User owner = createUser();
        seedSeries("66102");
        seedEpisode("66102", 1, 1);
        seedEpisode("66102", 1, 2);
        when(completionScopeLoader.load(eq(MediaReference.series("66102")), anyString(), anyString(), any()))
                .thenReturn(new VersionedCompletionScope(new CompletionScope(MediaReference.series("66102"),
                        java.util.Set.of(new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 2)), true),
                        "schedule-v1", Instant.now().plusSeconds(600)));
        byte[] archive = zip(Map.of("export/watched-shows.json",
                "[{\"plays\":1,\"show\":{\"title\":\"Confirmed series\",\"year\":2020,\"ids\":{\"tmdb\":66102}}}]"));
        ImportJobDTO job = createAndInspect(owner, archive, ImportSourceFormat.TRAKT_ZIP, ImportPlatform.TRAKT,
                "account-lucas");
        job = jobService.select(owner.getId(), job.id(), new ImportSelectionDTO(job.previewVersion(),
                true, false, List.of(), objectMapper.createObjectNode().put("sourceAccount", "account-lucas")));
        driveUntil(job.id(), ImportJobState.READY);
        ImportItem summary = items.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                job.id(), 1, PageRequest.of(0, 20)).getContent().stream()
                .filter(item -> item.getNormalizedKind() == ImportRecordKind.WATCHED_SUMMARY).findFirst().orElseThrow();
        var preview = objectMapper.valueToTree(jobService.preview(owner.getId(), job.id(), 0, 20));
        var watchScopePreview = preview.path("items").get(0).path("watchScopePreview");
        assertThat(watchScopePreview.path("scheduleVersion").asString()).isEqualTo("schedule-v1");
        assertThat(watchScopePreview.path("episodes").size()).isEqualTo(2);

        previewService.resolve(owner.getId(), job.id(), List.of(new ImportResolutionOperation.ConfirmWatchScope(
                summary.getId(), ImportResolutionOperation.WatchScope.ALL_RELEASED_EPISODES,
                "schedule-v1", List.of(MediaReference.episode("66102", 1, 1),
                        MediaReference.episode("66102", 1, 2)))),
                job.previewVersion());
        driveUntil(job.id(), ImportJobState.READY);
        job = jobService.get(owner.getId(), job.id());
        jobService.commit(owner.getId(), job.id(), new ImportCommitDTO(job.previewVersion(), job.previewDigest()));
        driveUntil(job.id(), ImportJobState.COMPLETED);

        assertThat(jdbc.queryForObject("select count(*) from diary_entries diary join contents content on content.id = diary.content_id where diary.user_id = ? and content.type = 'EPISODE' and content.series_tmdb_id = '66102'",
                Long.class, owner.getId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from diary_entries diary join contents content on content.id = diary.content_id where diary.user_id = ? and content.type = 'EPISODE' and content.series_tmdb_id = '66102' and diary.watched_date is not null",
                Long.class, owner.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from external_import_links where user_id = ? and object_kind = 'confirmed_episode' and target_type = 'DIARY_ENTRY'",
                Long.class, owner.getId())).isEqualTo(2L);
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void pausesWhenScheduleChangesAfterScopeConfirmationInsteadOfAddingUnseenEpisodes() throws Exception {
        User owner = createUser();
        seedSeries("66103");
        seedEpisode("66103", 1, 1);
        seedEpisode("66103", 1, 2);
        VersionedCompletionScope previewScope = completionScope("66103", "schedule-v1",
                java.util.Set.of(new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 2)));
        when(completionScopeLoader.load(eq(MediaReference.series("66103")), eq("US"), eq("en-US"), any()))
                .thenReturn(previewScope);
        byte[] archive = zip(Map.of("export/watched-shows.json",
                "[{\"plays\":1,\"show\":{\"title\":\"Changing series\",\"year\":2020,\"ids\":{\"tmdb\":66103}}}]"));
        ImportJobDTO job = createAndInspect(owner, archive, ImportSourceFormat.TRAKT_ZIP,
                ImportPlatform.TRAKT, "account-lucas");
        job = jobService.select(owner.getId(), job.id(), new ImportSelectionDTO(job.previewVersion(),
                true, false, List.of(), objectMapper.createObjectNode().put("sourceAccount", "account-lucas")));
        driveUntil(job.id(), ImportJobState.READY);
        ImportItem summary = items.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                job.id(), 1, PageRequest.of(0, 20)).getContent().stream()
                .filter(item -> item.getNormalizedKind() == ImportRecordKind.WATCHED_SUMMARY).findFirst().orElseThrow();
        var preview = objectMapper.valueToTree(jobService.preview(owner.getId(), job.id(), 0, 20));
        var scopePreview = preview.path("items").get(0).path("watchScopePreview");
        List<MediaReference> approvedEpisodes = new java.util.ArrayList<>();
        for (var coordinate : scopePreview.path("episodes")) {
            approvedEpisodes.add(MediaReference.episode("66103", coordinate.path("seasonNumber").asInt(),
                    coordinate.path("episodeNumber").asInt()));
        }
        previewService.resolve(owner.getId(), job.id(), List.of(new ImportResolutionOperation.ConfirmWatchScope(
                summary.getId(), ImportResolutionOperation.WatchScope.ALL_RELEASED_EPISODES,
                scopePreview.path("scheduleVersion").asString(), approvedEpisodes)), job.previewVersion());
        driveUntil(job.id(), ImportJobState.READY);
        jdbc.update("""
                INSERT INTO calendar_schedule_completeness
                    (id, group_type, series_tmdb_id, season_number, region, language,
                     expected_episode_count, complete, last_checked_at, scope_version)
                VALUES (?, 'SERIES', '66103', NULL, 'US', 'en-US', 2, TRUE, CURRENT_TIMESTAMP, 1)
                """, UUID.randomUUID());
        job = jobService.get(owner.getId(), job.id());
        jobService.commit(owner.getId(), job.id(), new ImportCommitDTO(job.previewVersion(), job.previewDigest()));
        driveUntil(job.id(), ImportJobState.READY);

        ImportItem pausedSummary = items.findById(summary.getId()).orElseThrow();
        assertThat(pausedSummary.getOutcome()).isEqualTo("NORMALIZATION_ISSUE");
        assertThat(pausedSummary.getIssue()).contains("WATCH_SCOPE_SCHEDULE_CHANGED");
        assertThat(jdbc.queryForObject("select count(*) from diary_entries diary join contents content on content.id = diary.content_id where diary.user_id = ? and content.type = 'EPISODE' and content.series_tmdb_id = '66103'",
                Long.class, owner.getId())).isZero();
        assertThat(contents.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                "66103", 1, 3, ContentType.EPISODE)).isEmpty();
        verifyNoInteractions(tmdbClient);
    }

    private ImportJobDTO createAndInspect(User owner, byte[] archive, ImportSourceFormat format,
                                          ImportPlatform platform, String account) {
        MockMultipartFile file = new MockMultipartFile("file", "export.zip", "application/zip", archive);
        ImportJobDTO job = jobService.create(owner.getId(), file, ImportInputKind.PLATFORM_ARCHIVE,
                format, platform, account, "UTC");
        worker.runAvailable(1);
        ImportJobDTO inspected = jobService.get(owner.getId(), job.id());
        assertThat(inspected.state()).isEqualTo(ImportJobState.AWAITING_SELECTION);
        return inspected;
    }

    private void driveUntil(UUID jobId, ImportJobState expected) {
        for (int attempt = 0; attempt < 12; attempt++) {
            ImportJob job = jobs.findById(jobId).orElseThrow();
            if (job.getState() == expected) return;
            job.setNextRunAt(LocalDateTime.now().minusSeconds(2));
            jobs.saveAndFlush(job);
            worker.runAvailable(1);
        }
        assertThat(jobs.findById(jobId).orElseThrow().getState()).isEqualTo(expected);
    }

    private void driveUntilWaitingForResolution(UUID jobId) {
        for (int attempt = 0; attempt < 12; attempt++) {
            ImportJob job = jobs.findById(jobId).orElseThrow();
            if (job.getState() == ImportJobState.RESOLVING && !job.isWorkPending()) return;
            job.setNextRunAt(LocalDateTime.now(Clock.systemUTC()).minusSeconds(2));
            jobs.saveAndFlush(job);
            worker.runAvailable(1);
        }
        ImportJob waiting = jobs.findById(jobId).orElseThrow();
        assertThat(waiting.getState()).isEqualTo(ImportJobState.RESOLVING);
        assertThat(waiting.isWorkPending()).isFalse();
    }

    private User createUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        LocalDateTime now = LocalDateTime.now();
        return users.saveAndFlush(User.builder().username("archive" + suffix).name("Archive " + suffix)
                .email("archive" + suffix + "@example.com").password("Password123")
                .createdAt(now).updatedAt(now).build());
    }

    private void seedMovie(String id) {
        LocalDateTime now = LocalDateTime.now();
        contents.saveAndFlush(Content.builder().type(ContentType.MOVIE).tmdbId(id)
                .runtimeMinutes(120).genres(List.of("Drama")).releaseYear(2020).countries(List.of("US"))
                .createdAt(now).updatedAt(now).build());
    }

    private String watchedMovieEvent(String id, String watchedAt) {
        return "[{\"id\":" + id + ",\"watched_at\":\"" + watchedAt
                + "\",\"action\":\"scrobble\",\"type\":\"movie\",\"movie\":{"
                + "\"title\":\"The Example\",\"year\":2001,\"ids\":{\"trakt\":901}}}]";
    }

    private void seedSeries(String id) {
        LocalDateTime now = LocalDateTime.now();
        contents.saveAndFlush(Content.builder().type(ContentType.SERIES).tmdbId(id)
                .genres(List.of("Drama")).releaseYear(2020).countries(List.of("US"))
                .createdAt(now).updatedAt(now).build());
    }

    private void seedEpisode(String seriesId, int season, int episode) {
        LocalDateTime now = LocalDateTime.now();
        contents.saveAndFlush(Content.builder().type(ContentType.EPISODE).seriesTmdbId(seriesId)
                .seasonNumber(season).episodeNumber(episode).runtimeMinutes(45)
                .createdAt(now).updatedAt(now).build());
    }

    private VersionedCompletionScope completionScope(String seriesId, String scheduleVersion,
                                                     java.util.Set<EpisodeCoordinate> episodes) {
        return new VersionedCompletionScope(new CompletionScope(MediaReference.series(seriesId), episodes, true),
                scheduleVersion, Instant.now().plusSeconds(600));
    }

    private byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(entries).entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
