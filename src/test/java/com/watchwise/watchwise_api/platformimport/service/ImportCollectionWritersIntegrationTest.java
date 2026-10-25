package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.entity.ImportedWatchAssertion;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.ImportedWatchAssertionRepository;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.repository.ExternalImportLinkRepository;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportUnitTransaction;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import com.watchwise.watchwise_api.watchlist.service.WatchlistEntryService;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "app.import.worker.enabled=false",
        "app.import.cleanup.enabled=false"
})
@Testcontainers
class ImportCollectionWritersIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private ContentRepository contents;
    @Autowired private ImportJobRepository jobs;
    @Autowired private ImportItemRepository items;
    @Autowired private ImportUnitTransaction unitTransaction;
    @Autowired private UserListRepository userLists;
    @Autowired private UserListItemRepository userListItems;
    @Autowired private WatchlistEntryRepository watchlistEntries;
    @Autowired private WatchlistEntryService watchlistEntryService;
    @Autowired private DiaryEntryRepository diaryEntries;
    @Autowired private ImportedWatchAssertionRepository assertions;
    @Autowired private ExternalImportLinkRepository importLinks;
    @Autowired private UserListItemService userListItemService;
    @Autowired private ObjectMapper mapper;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE import_items, external_import_links, user_list_items, user_lists, contents, users CASCADE");
    }

    @Test
    void createsPublicListInSourceOrderAndSkipsDuplicateContent() throws Exception {
        User owner = createUser();
        createMovie("51001");
        createMovie("51002");
        ImportJob job = createJob(owner);
        ImportItem metadata = createListMetadata(job, "letterboxd-list-https://letterboxd.com/lists/example/",
                "Letterboxd Classics", "Curated movie list", 0);
        ImportItem first = createListItem(job, "one", "letterboxd-list-https://letterboxd.com/lists/example/",
                "51001", 0);
        ImportItem second = createListItem(job, "two", "letterboxd-list-https://letterboxd.com/lists/example/",
                "51002", 1);
        ImportItem duplicate = createListItem(job, "three", "letterboxd-list-https://letterboxd.com/lists/example/",
                "51001", 2);

        apply(job, metadata);

        UserList imported = userLists.findByUserId(owner.getId()).stream().findFirst().orElseThrow();
        assertThat(imported.getName()).isEqualTo("Letterboxd Classics");
        assertThat(imported.getDescription()).isEqualTo("Curated movie list");
        assertThat(imported.getVisibility().name()).isEqualTo("PUBLIC");
        List<UserListItem> saved = userListItems.findByUserListIdWithContentAndChildListOrderByPositionAsc(imported.getId());
        assertThat(saved).extracting(item -> item.getContent().getTmdbId()).containsExactly("51001", "51002");
        assertThat(saved).extracting(UserListItem::getPosition).containsExactly(1, 2);
        assertThat(saved).extracting(UserListItem::getDescription).containsExactly("one", "two");
        assertThat(items.findById(first.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        assertThat(items.findById(second.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        assertThat(items.findById(duplicate.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
    }

    @Test
    void appendsEligibleWatchlistItemsAfterExistingAndSkipsWatchedAndDuplicateContent() throws Exception {
        User owner = createUser();
        for (String id : List.of("52001", "52002", "52003", "52004")) createMovie(id);
        addWatchlistItem(owner, "52001", 1);
        addWatchlistItem(owner, "52002", 2);
        ImportJob job = createJob(owner);
        ImportItem existing = createWatchlistItem(job, "existing-b", "52002", 0);
        ImportItem firstNew = createWatchlistItem(job, "new-c-1", "52003", 1);
        ImportItem duplicate = createWatchlistItem(job, "new-c-2", "52003", 2);
        ImportItem sourceWatched = createWatchlistItem(job, "watched-d", "52004", 3);
        createWatchedMarker(job, "source-watch-d", "52004", 10);

        apply(job, existing);

        assertThat(watchlistEntries.findByUserIdOrderByPositionAsc(owner.getId()))
                .extracting(entry -> entry.getContent().getTmdbId())
                .containsExactly("52001", "52002", "52003");
        assertThat(items.findById(existing.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(items.findById(firstNew.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        assertThat(items.findById(duplicate.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(items.findById(sourceWatched.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
    }

    @Test
    void appliesWatchlistItemsByRankWhenSourceRowsAreReversed() throws Exception {
        User owner = createUser();
        createMovie("52006");
        createMovie("52007");
        ImportJob job = createJob(owner);
        ImportItem rankTwo = createRankedWatchlistItem(job, "rank-two", "52006", 0, 2);
        ImportItem rankOne = createRankedWatchlistItem(job, "rank-one", "52007", 1, 1);

        ImportItem firstPendingOrdinal = items.findApplyPending(job.getId(), 1, PageRequest.of(0, 200)).getFirst();
        apply(job, firstPendingOrdinal);

        assertThat(watchlistEntries.findByUserIdOrderByPositionAsc(owner.getId()))
                .extracting(entry -> entry.getContent().getTmdbId()).containsExactly("52007", "52006");
        assertThat(items.findById(rankTwo.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        assertThat(items.findById(rankOne.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
    }

    @Test
    void advancesWatchlistCursorAcrossEqualRanksWithoutSkippingOrRepeating() throws Exception {
        User owner = createUser();
        ImportJob job = createJob(owner);
        createRankedWatchlistItem(job, "equal-rank-first", "52008", 0, 5);
        createRankedWatchlistItem(job, "equal-rank-second", "52009", 1, 5);
        createRankedWatchlistItem(job, "equal-rank-third", "52010", 2, 5);
        long afterRank = -1;
        long afterOrdinal = -1;
        UUID afterId = new UUID(0, 0);
        List<String> seen = new java.util.ArrayList<>();

        while (seen.size() < 4) {
            List<ImportItem> page = items.findWatchlistItemsAfter(job.getId(), 1, afterRank, afterOrdinal,
                    afterId.toString(), 1);
            if (page.isEmpty()) break;
            ImportItem last = page.getFirst();
            seen.add(last.getSourceId());
            afterRank = 5;
            afterOrdinal = last.getOrdinal();
            afterId = last.getId();
        }

        assertThat(seen).containsExactly("equal-rank-first", "equal-rank-second", "equal-rank-third");
    }

    @Test
    void advancesCallerAcrossRankPagesWhenItsSourceOrdinalIsOutsideTheFirstBatch() throws Exception {
        User owner = createUser();
        LocalDateTime now = LocalDateTime.now();
        List<Content> movies = new java.util.ArrayList<>(201);
        List<ImportItem> staged = new java.util.ArrayList<>(201);
        ImportJob job = createJob(owner);
        for (int ordinal = 0; ordinal < 201; ordinal++) {
            String movieTmdbId = Integer.toString(60_001 + ordinal);
            int rank = 201 - ordinal;
            movies.add(Content.builder().type(ContentType.MOVIE).tmdbId(movieTmdbId)
                    .createdAt(now).updatedAt(now).build());
            var payload = mapper.createObjectNode().put("title", "Movie " + movieTmdbId).put("rank", rank);
            staged.add(ImportItem.builder().jobId(job.getId()).planGeneration(1).ordinal(ordinal)
                    .normalizedKind(ImportRecordKind.WATCHLIST_ITEM).sourceNamespace(job.getSourceNamespace())
                    .objectKind("watchlist_content").sourceId("rank-" + rank)
                    .payloadJson(mapper.writeValueAsString(payload))
                    .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(
                            MediaReference.movie(movieTmdbId))))
                    .resolvedContentKey("CONTENT|MOVIE|" + movieTmdbId).outcome("MATCHED").build());
        }
        contents.saveAllAndFlush(movies);
        List<ImportItem> savedItems = items.saveAllAndFlush(staged);
        ImportItem firstPendingOrdinal = savedItems.getFirst();

        apply(job, firstPendingOrdinal);

        assertThat(items.findById(firstPendingOrdinal.getId()).orElseThrow().getOutcome()).isEqualTo("MATCHED");
        assertThat(watchlistEntries.count()).isEqualTo(200L);

        apply(job, firstPendingOrdinal);

        assertThat(items.findById(firstPendingOrdinal.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        assertThat(watchlistEntries.findByUserIdOrderByPositionAsc(owner.getId()))
                .extracting(entry -> entry.getContent().getTmdbId())
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 201)
                        .mapToObj(offset -> Integer.toString(60_201 - offset)).toList());
        assertThat(items.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                job.getId(), 1, null, ImportRecordKind.WATCHLIST_ITEM))
                .extracting(ImportItem::getOutcome).containsOnly("APPLIED");
    }

    @Test
    void skipsWatchlistItemsWithDiaryOrUnknownAssertionEvidence() throws Exception {
        User owner = createUser();
        createMovie("52005");
        Content series = contents.saveAndFlush(Content.builder().type(ContentType.SERIES).tmdbId("53001")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        Content watchedMovie = contents.findByTmdbIdAndType("52005", ContentType.MOVIE).orElseThrow();
        diaryEntries.saveAndFlush(DiaryEntry.builder().user(owner).content(watchedMovie).watchNumber(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        assertions.saveAndFlush(ImportedWatchAssertion.builder().user(owner).content(series)
                .sourceNamespace("trakt:account-test").objectKind("watched_summary").sourceId("series-53001")
                .coverage("UNKNOWN").createdAt(LocalDateTime.now()).build());
        ImportJob job = createJob(owner);
        ImportItem watchedMovieItem = createWatchlistItem(job, "already-watched-movie", "52005", 0);
        ImportItem assertedSeriesItem = createWatchlistSeriesItem(job, "asserted-series", "53001", 1);

        apply(job, watchedMovieItem);

        assertThat(watchlistEntries.findByUserIdOrderByPositionAsc(owner.getId())).isEmpty();
        assertThat(items.findById(watchedMovieItem.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(items.findById(assertedSeriesItem.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
    }

    @Test
    void skipsSeriesFromWatchlistWhenOnlyEpisodeOrSeasonWatchEvidenceIsStaged() throws Exception {
        User owner = createUser();
        LocalDateTime now = LocalDateTime.now();
        contents.saveAndFlush(Content.builder().type(ContentType.SERIES).tmdbId("53002")
                .createdAt(now).updatedAt(now).build());
        contents.saveAndFlush(Content.builder().type(ContentType.SERIES).tmdbId("53003")
                .createdAt(now).updatedAt(now).build());
        ImportJob job = createJob(owner);
        ImportItem episodeWatchedSeries = createWatchlistSeriesItem(job, "series-with-episode-marker", "53002", 0);
        ImportItem seasonWatchedSeries = createWatchlistSeriesItem(job, "series-with-season-marker", "53003", 1);
        createWatchedDetailMarker(job, "episode-watch-marker", MediaReference.episode("53002", 1, 1), 10);
        createWatchedDetailMarker(job, "season-watch-marker", MediaReference.season("53003", 1), 11);

        apply(job, episodeWatchedSeries);

        assertThat(watchlistEntries.findByUserIdOrderByPositionAsc(owner.getId())).isEmpty();
        assertThat(items.findById(episodeWatchedSeries.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(items.findById(seasonWatchedSeries.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(diaryEntries.count()).isZero();
    }

    @Test
    void changedListManifestReusesDestinationAndAddsOnlyNewMembers() throws Exception {
        User owner = createUser();
        for (String id : List.of("54001", "54002", "54003")) createMovie(id);
        String listSourceId = "letterboxd-list-https://letterboxd.com/lists/revised/";
        ImportJob firstJob = createJob(owner);
        ImportItem firstMetadata = createListMetadata(firstJob, listSourceId, "Original title", "Original description", 0);
        createListItem(firstJob, "first-a", listSourceId, "54001", 1);
        createListItem(firstJob, "first-b", listSourceId, "54002", 2);
        apply(firstJob, firstMetadata);
        completeJob(firstJob);

        ImportJob nextJob = createJob(owner);
        ImportItem nextMetadata = createListMetadata(nextJob, listSourceId, "Changed upstream title", "Changed upstream description", 0);
        ImportItem existingMember = createListItem(nextJob, "second-b", listSourceId, "54002", 1);
        ImportItem newMember = createListItem(nextJob, "second-c", listSourceId, "54003", 2);
        apply(nextJob, nextMetadata);

        List<UserList> ownerLists = userLists.findByUserId(owner.getId());
        assertThat(ownerLists).hasSize(1);
        assertThat(ownerLists.getFirst().getName()).isEqualTo("Original title");
        assertThat(ownerLists.getFirst().getDescription()).isEqualTo("Original description");
        List<UserListItem> saved = userListItems.findByUserListIdWithContentAndChildListOrderByPositionAsc(ownerLists.getFirst().getId());
        assertThat(saved).extracting(item -> item.getContent().getTmdbId())
                .containsExactly("54001", "54002", "54003");
        assertThat(saved.get(1).getDescription()).isEqualTo("first-b");
        assertThat(items.findById(existingMember.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(items.findById(newMember.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
    }

    @Test
    void createsASelectedEmptyListAsPublic() throws Exception {
        User owner = createUser();
        ImportJob job = createJob(owner);
        ImportItem metadata = createListMetadata(job, "trakt-list-55", "Empty list", null, 0, 0L);

        apply(job, metadata);

        UserList empty = userLists.findByUserId(owner.getId()).getFirst();
        assertThat(empty.getVisibility().name()).isEqualTo("PUBLIC");
        assertThat(userListItems.findByUserListIdWithContentAndChildListOrderByPositionAsc(empty.getId())).isEmpty();
    }

    @Test
    void doesNotCreateListWhenDeclaredSourceItemCountExceedsStagedItems() throws Exception {
        User owner = createUser();
        ImportJob job = createJob(owner);
        ImportItem metadata = createListMetadata(job, "trakt-list-incomplete", "Incomplete list", null, 0, 2L);

        apply(job, metadata);

        assertThat(userLists.findByUserId(owner.getId())).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM external_import_links
                WHERE user_id = ? AND source_namespace = ? AND state = 'APPLIED'
                """, Long.class, owner.getId(), job.getSourceNamespace())).isZero();
        ImportItem result = items.findById(metadata.getId()).orElseThrow();
        assertThat(result.getOutcome()).isEqualTo("NORMALIZATION_ISSUE");
        assertThat(result.getIssue()).startsWith("LIST_ITEM_COUNT_MISMATCH:");
        ImportJob paused = jobs.findById(job.getId()).orElseThrow();
        assertThat(paused.getState()).isEqualTo(ImportJobState.READY);
        assertThat(paused.isWorkPending()).isFalse();
    }

    @Test
    void ordersTraktListMembersByRankEvenWhenSourceRowsAreOutOfOrder() throws Exception {
        User owner = createUser();
        createMovie("57001");
        createMovie("57002");
        String listSourceId = "trakt-list-rank-test";
        ImportJob job = createJob(owner);
        ImportItem metadata = createListMetadata(job, listSourceId, "Ranked list", null, 0);
        createRankedListItem(job, "rank-two", listSourceId, "57001", 0, 2);
        createRankedListItem(job, "rank-one", listSourceId, "57002", 1, 1);

        apply(job, metadata);

        UserList list = userLists.findByUserId(owner.getId()).getFirst();
        assertThat(userListItems.findByUserListIdWithContentAndChildListOrderByPositionAsc(list.getId()))
                .extracting(item -> item.getContent().getTmdbId()).containsExactly("57002", "57001");
    }

    @Test
    void deletingAnImportedListItemTombstonesItAndPreventsReimport() throws Exception {
        User owner = createUser();
        createMovie("55001");
        String listSourceId = "letterboxd-list-https://letterboxd.com/lists/tombstone/";
        ImportJob firstJob = createJob(owner);
        ImportItem metadata = createListMetadata(firstJob, listSourceId, "Tombstone list", null, 0);
        createListItem(firstJob, "same-source-item", listSourceId, "55001", 1);
        apply(firstJob, metadata);
        completeJob(firstJob);
        UserList list = userLists.findByUserId(owner.getId()).getFirst();
        UserListItem savedItem = userListItems.findByUserListIdWithContentAndChildListOrderByPositionAsc(list.getId()).getFirst();

        userListItemService.removeItem(owner.getId(), list.getId(), savedItem.getId());

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM external_import_links
                WHERE user_id = ? AND target_type = 'USER_LIST_ITEM' AND target_id = ? AND state = 'TOMBSTONE'
                """, Long.class, owner.getId(), savedItem.getId())).isEqualTo(1L);

        ImportJob repeatedJob = createJob(owner);
        ImportItem repeatedMetadata = createListMetadata(repeatedJob, listSourceId, "Tombstone list", null, 0);
        ImportItem repeatedItem = createListItem(repeatedJob, "same-source-item", listSourceId, "55001", 1);
        apply(repeatedJob, repeatedMetadata);
        apply(repeatedJob, repeatedItem);

        assertThat(userListItems.findByUserListIdWithContentAndChildListOrderByPositionAsc(list.getId())).isEmpty();
        assertThat(items.findById(repeatedItem.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
    }

    @Test
    void deletingAnImportedWatchlistItemTombstonesItAndPreventsReimport() throws Exception {
        User owner = createUser();
        createMovie("56001");
        ImportJob firstJob = createJob(owner);
        ImportItem firstItem = createWatchlistItem(firstJob, "same-watch-source", "56001", 0);
        apply(firstJob, firstItem);
        completeJob(firstJob);
        WatchlistEntry saved = watchlistEntries.findByUserIdOrderByPositionAsc(owner.getId()).getFirst();

        watchlistEntryService.removeEntry(owner.getId(), ContentType.MOVIE, saved.getId());

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM external_import_links
                WHERE user_id = ? AND target_type = 'WATCHLIST_ENTRY' AND target_id = ? AND state = 'TOMBSTONE'
                """, Long.class, owner.getId(), saved.getId())).isEqualTo(1L);

        ImportJob repeatedJob = createJob(owner);
        ImportItem repeatedItem = createWatchlistItem(repeatedJob, "same-watch-source", "56001", 0);
        apply(repeatedJob, repeatedItem);

        assertThat(watchlistEntries.findByUserIdOrderByPositionAsc(owner.getId())).isEmpty();
        assertThat(items.findById(repeatedItem.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
    }

    private User createUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        LocalDateTime now = LocalDateTime.now();
        return users.saveAndFlush(User.builder().username("collection" + suffix).name("Collection Import")
                .email("collection" + suffix + "@example.test").password("test-password")
                .preferredLanguage("en-US").preferredRegion("US").createdAt(now).updatedAt(now).build());
    }

    private void createMovie(String tmdbId) {
        LocalDateTime now = LocalDateTime.now();
        contents.saveAndFlush(Content.builder().type(ContentType.MOVIE).tmdbId(tmdbId)
                .createdAt(now).updatedAt(now).build());
    }

    private void addWatchlistItem(User owner, String tmdbId, int position) {
        LocalDateTime now = LocalDateTime.now();
        var movie = contents.findByTmdbIdAndType(tmdbId, ContentType.MOVIE).orElseThrow();
        watchlistEntries.saveAndFlush(WatchlistEntry.builder().user(owner).content(movie)
                .type(ContentType.MOVIE).position(position).createdAt(now).updatedAt(now).build());
    }

    private ImportJob createJob(User owner) {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        return jobs.saveAndFlush(ImportJob.builder().userId(owner.getId())
                .inputKind(ImportInputKind.PLATFORM_ARCHIVE).sourceFormat(ImportSourceFormat.LETTERBOXD_ZIP)
                .platform(ImportPlatform.LETTERBOXD).sourceAccountId("letterboxd-test-account")
                .sourceNamespace("letterboxd:account-test").state(ImportJobState.IMPORTING).previewVersion(1)
                .selectionJson("{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{}}")
                .timezone("UTC").storageKey(UUID.randomUUID().toString()).uploadSizeBytes(1)
                .workPhase("APPLY").workPending(true).leaseToken(UUID.randomUUID())
                .leaseUntil(now.plusMinutes(2)).createdAt(now).updatedAt(now).expiresAt(now.plusDays(1)).build());
    }

    private ImportItem createListMetadata(ImportJob job, String sourceId, String name, String description, long ordinal)
            throws Exception {
        return createListMetadata(job, sourceId, name, description, ordinal, null);
    }

    private ImportItem createListMetadata(ImportJob job, String sourceId, String name, String description,
                                          long ordinal, Long sourceItemCount) throws Exception {
        var payload = mapper.createObjectNode().put("title", name).put("description", description);
        if (sourceItemCount != null) payload.put("sourceItemCount", sourceItemCount);
        ImportItem item = ImportItem.builder().jobId(job.getId()).planGeneration(1).ordinal(ordinal)
                .normalizedKind(ImportRecordKind.LIST_METADATA).sourceNamespace(job.getSourceNamespace())
                .objectKind("list_metadata").sourceId(sourceId).payloadJson(mapper.writeValueAsString(payload))
                .outcome("MATCHED").build();
        return items.saveAndFlush(item);
    }

    private ImportItem createListItem(ImportJob job, String sourceId, String parentSourceId,
                                      String movieTmdbId, long ordinal) throws Exception {
        var payload = mapper.createObjectNode().put("title", "Movie " + movieTmdbId)
                .put("position", ordinal + 1).put("description", sourceId);
        ImportItem item = ImportItem.builder().jobId(job.getId()).planGeneration(1).ordinal(ordinal + 1)
                .normalizedKind(ImportRecordKind.LIST_ITEM).sourceNamespace(job.getSourceNamespace())
                .objectKind("list_item").sourceId(sourceId).parentSourceId(parentSourceId)
                .payloadJson(mapper.writeValueAsString(payload))
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(MediaReference.movie(movieTmdbId))))
                .resolvedContentKey("CONTENT|MOVIE|" + movieTmdbId).outcome("MATCHED").build();
        return items.saveAndFlush(item);
    }

    private ImportItem createRankedListItem(ImportJob job, String sourceId, String parentSourceId,
                                            String movieTmdbId, long ordinal, int rank) throws Exception {
        var payload = mapper.createObjectNode().put("title", "Movie " + movieTmdbId)
                .put("rank", rank).put("description", sourceId);
        ImportItem item = ImportItem.builder().jobId(job.getId()).planGeneration(1).ordinal(ordinal + 1)
                .normalizedKind(ImportRecordKind.LIST_ITEM).sourceNamespace(job.getSourceNamespace())
                .objectKind("list_item").sourceId(sourceId).parentSourceId(parentSourceId)
                .payloadJson(mapper.writeValueAsString(payload))
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(MediaReference.movie(movieTmdbId))))
                .resolvedContentKey("CONTENT|MOVIE|" + movieTmdbId).outcome("MATCHED").build();
        return items.saveAndFlush(item);
    }

    private ImportItem createWatchlistItem(ImportJob job, String sourceId, String movieTmdbId, long ordinal)
            throws Exception {
        var payload = mapper.createObjectNode().put("title", "Movie " + movieTmdbId);
        ImportItem item = ImportItem.builder().jobId(job.getId()).planGeneration(1).ordinal(ordinal)
                .normalizedKind(ImportRecordKind.WATCHLIST_ITEM).sourceNamespace(job.getSourceNamespace())
                .objectKind("watchlist_content").sourceId(sourceId).payloadJson(mapper.writeValueAsString(payload))
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(MediaReference.movie(movieTmdbId))))
                .resolvedContentKey("CONTENT|MOVIE|" + movieTmdbId).outcome("MATCHED").build();
        return items.saveAndFlush(item);
    }

    private ImportItem createRankedWatchlistItem(ImportJob job, String sourceId, String movieTmdbId,
                                                 long ordinal, int rank) throws Exception {
        var payload = mapper.createObjectNode().put("title", "Movie " + movieTmdbId).put("rank", rank);
        ImportItem item = ImportItem.builder().jobId(job.getId()).planGeneration(1).ordinal(ordinal)
                .normalizedKind(ImportRecordKind.WATCHLIST_ITEM).sourceNamespace(job.getSourceNamespace())
                .objectKind("watchlist_content").sourceId(sourceId).payloadJson(mapper.writeValueAsString(payload))
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(MediaReference.movie(movieTmdbId))))
                .resolvedContentKey("CONTENT|MOVIE|" + movieTmdbId).outcome("MATCHED").build();
        return items.saveAndFlush(item);
    }

    private ImportItem createWatchlistSeriesItem(ImportJob job, String sourceId, String seriesTmdbId, long ordinal)
            throws Exception {
        var payload = mapper.createObjectNode().put("title", "Series " + seriesTmdbId);
        ImportItem item = ImportItem.builder().jobId(job.getId()).planGeneration(1).ordinal(ordinal)
                .normalizedKind(ImportRecordKind.WATCHLIST_ITEM).sourceNamespace(job.getSourceNamespace())
                .objectKind("watchlist_content").sourceId(sourceId).payloadJson(mapper.writeValueAsString(payload))
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(MediaReference.series(seriesTmdbId))))
                .resolvedContentKey("CONTENT|SERIES|" + seriesTmdbId).outcome("MATCHED").build();
        return items.saveAndFlush(item);
    }

    private ImportItem createWatchedMarker(ImportJob job, String sourceId, String movieTmdbId, long ordinal)
            throws Exception {
        var payload = mapper.createObjectNode().put("title", "Movie " + movieTmdbId);
        ImportItem item = ImportItem.builder().jobId(job.getId()).planGeneration(1).ordinal(ordinal)
                .normalizedKind(ImportRecordKind.WATCHED_MARKER).sourceNamespace(job.getSourceNamespace())
                .objectKind("watched_content").sourceId(sourceId).payloadJson(mapper.writeValueAsString(payload))
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(MediaReference.movie(movieTmdbId))))
                .resolvedContentKey("CONTENT|MOVIE|" + movieTmdbId).outcome("MATCHED").build();
        return items.saveAndFlush(item);
    }

    private ImportItem createWatchedDetailMarker(ImportJob job, String sourceId, MediaReference reference,
                                                 long ordinal) throws Exception {
        var payload = mapper.createObjectNode().put("title", "Watched detail " + sourceId);
        ImportItem item = ImportItem.builder().jobId(job.getId()).planGeneration(1).ordinal(ordinal)
                .normalizedKind(ImportRecordKind.WATCHED_MARKER).sourceNamespace(job.getSourceNamespace())
                .objectKind("watched_content").sourceId(sourceId).payloadJson(mapper.writeValueAsString(payload))
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(reference)))
                .resolvedContentKey(reference.type() == ContentType.EPISODE
                        ? "CONTENT|EPISODE|" + reference.seriesTmdbId() + "|" + reference.seasonNumber()
                        + "|" + reference.episodeNumber()
                        : "CONTENT|SEASON|" + reference.seriesTmdbId() + "|" + reference.seasonNumber())
                .outcome("MATCHED").build();
        return items.saveAndFlush(item);
    }

    private void apply(ImportJob job, ImportItem item) {
        ImportJob persisted = jobs.findById(job.getId()).orElseThrow();
        ImportItem persistedItem = items.findById(item.getId()).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(ImportJobState.IMPORTING);
        assertThat(persisted.isWorkPending()).isTrue();
        assertThat(persisted.getLeaseToken()).isNotNull();
        assertThat(persisted.getLeaseUntil()).isAfter(LocalDateTime.now());
        assertThat(persistedItem.getJobId()).isEqualTo(persisted.getId());
        assertThat(persistedItem.getPlanGeneration()).isEqualTo(1);
        assertThat(unitTransaction.apply(new ImportExecutionContext(job.getUserId(), job.getId(),
                persisted.getPreviewVersion(), 1, persisted.getSourceNamespace(), persisted.getLeaseToken()), item.getId())).isTrue();
    }

    private void completeJob(ImportJob job) {
        ImportJob persisted = jobs.findById(job.getId()).orElseThrow();
        persisted.setState(ImportJobState.COMPLETED);
        persisted.setWorkPending(false);
        persisted.setLeaseToken(null);
        persisted.setLeaseUntil(null);
        jobs.saveAndFlush(persisted);
    }
}
