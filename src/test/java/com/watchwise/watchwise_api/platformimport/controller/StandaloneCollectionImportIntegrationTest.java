package com.watchwise.watchwise_api.platformimport.controller;

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
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "app.import.worker.enabled=false",
        "app.import.cleanup.enabled=false"
})
@Testcontainers
class StandaloneCollectionImportIntegrationTest {

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
    void standaloneWatchlistAppendsOnlyEligibleDistinctContentAndSucceedsWithZeroInsertions() throws Exception {
        User owner = createUser();
        for (String id : List.of("71001", "71002", "71003", "71004")) createMovie(id);
        addWatchlistItem(owner, "71001", 1);
        addWatchlistItem(owner, "71002", 2);
        ImportJob job = createJob(owner, ImportInputKind.SINGLE_WATCHLIST);
        ImportItem b = createWatchlistItem(job, "b", "71002", 0);
        createWatchlistItem(job, "c", "71003", 1);
        createWatchlistItem(job, "c-duplicate", "71003", 2);
        createWatchlistItem(job, "d", "71004", 3);

        apply(job, b);

        assertThat(watchlistEntries.findByUserIdOrderByPositionAsc(owner.getId()))
                .extracting(entry -> entry.getContent().getTmdbId())
                .containsExactly("71001", "71002", "71003", "71004");
        assertThat(items.findById(b.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");

        completeJob(job);
        ImportJob repeated = createJob(owner, ImportInputKind.SINGLE_WATCHLIST);
        ImportItem existing = createWatchlistItem(repeated, "new-source-id", "71002", 0);
        apply(repeated, existing);
        assertThat(items.findById(existing.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(watchlistEntries.findByUserIdOrderByPositionAsc(owner.getId())).hasSize(4);
    }

    @Test
    void semanticStandaloneListReuseDoesNotOverwriteEditsAndChangedContentsStayDistinct() throws Exception {
        User owner = createUser();
        createMovie("72001");
        createMovie("72002");
        ImportJob firstJob = createJob(owner, ImportInputKind.SINGLE_LIST);
        ImportItem first = createListMetadata(firstJob, "upload-one.csv", "Same name", "A list", 0);
        createListItem(firstJob, "first", first.getSourceId(), "72001", 1);
        apply(firstJob, first);
        completeJob(firstJob);
        UUID firstListId = userLists.findByUserId(owner.getId()).getFirst().getId();
        jdbc.update("UPDATE user_lists SET name = ? WHERE id = ?", "My edited name", firstListId);

        ImportJob againJob = createJob(owner, ImportInputKind.SINGLE_LIST);
        ImportItem again = createListMetadata(againJob, "renamed-upload.csv", " Same name ", " A list ", 0);
        createListItem(againJob, "first", again.getSourceId(), "72001", 1);
        apply(againJob, again);
        completeJob(againJob);

        assertThat(userLists.findByUserId(owner.getId())).hasSize(1);
        assertThat(userLists.findById(firstListId).orElseThrow().getName()).isEqualTo("My edited name");

        ImportJob changedJob = createJob(owner, ImportInputKind.SINGLE_LIST);
        ImportItem changed = createListMetadata(changedJob, "third-file.csv", "Same name", "A list", 0);
        createListItem(changedJob, "second", changed.getSourceId(), "72002", 1);
        apply(changedJob, changed);
        assertThat(userLists.findByUserId(owner.getId())).hasSize(2);
    }

    @Test
    void explicitExternalIdentityAliasesLocalListAndConvergesWithArchive() throws Exception {
        User owner = createUser();
        createMovie("73001");
        ImportJob localJob = createJob(owner, ImportInputKind.SINGLE_LIST);
        ImportItem local = createListMetadata(localJob, "local-upload", "Archive list", null, 0);
        createListItem(localJob, "same-description", local.getSourceId(), "73001", 1);
        apply(localJob, local);
        completeJob(localJob);
        UUID destination = userLists.findByUserId(owner.getId()).getFirst().getId();

        ImportJob externalJob = createJob(owner, ImportInputKind.SINGLE_LIST);
        ImportItem external = createListMetadata(externalJob, "external-upload", "Archive list", null, 0);
        setExternalIdentity(external, "73077");
        createListItem(externalJob, "same-description", external.getSourceId(), "73001", 1);
        apply(externalJob, external);
        completeJob(externalJob);
        assertThat(userLists.findByUserId(owner.getId())).hasSize(1);

        ImportJob archiveJob = createJob(owner, ImportInputKind.PLATFORM_ARCHIVE);
        ImportItem archive = createListMetadata(archiveJob, "73077", "Changed upstream name", null, 0);
        setExternalIdentity(archive, "73077");
        createListItem(archiveJob, "same-description", archive.getSourceId(), "73001", 1);
        apply(archiveJob, archive);
        assertThat(userLists.findByUserId(owner.getId())).hasSize(1);
        assertThat(userLists.findById(destination).orElseThrow().getName()).isEqualTo("Archive list");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM external_import_links
                WHERE user_id = ? AND object_kind = 'user_list' AND target_id = ? AND state = 'APPLIED'
                """, Long.class, owner.getId(), destination)).isEqualTo(2L);
    }

    @Test
    void repeatedSemanticListCannotSilentlyRelinkToAnotherDestination() throws Exception {
        User owner = createUser();
        createMovie("74001");
        ImportJob firstJob = createJob(owner, ImportInputKind.SINGLE_LIST);
        ImportItem first = createListMetadata(firstJob, "initial", "Protected list", null, 0);
        createListItem(firstJob, "description", first.getSourceId(), "74001", 1);
        apply(firstJob, first);
        completeJob(firstJob);

        LocalDateTime now = LocalDateTime.now();
        UserList other = userLists.saveAndFlush(UserList.builder().user(owner).name("Other destination")
                .visibility(com.watchwise.watchwise_api.userlist.entity.UserListVisibility.PRIVATE)
                .createdAt(now).updatedAt(now).build());
        ImportJob retry = createJob(owner, ImportInputKind.SINGLE_LIST);
        ImportItem repeated = createListMetadata(retry, "renamed", "Protected list", null, 0);
        createListItem(retry, "description", repeated.getSourceId(), "74001", 1);
        var payload = (tools.jackson.databind.node.ObjectNode) mapper.readTree(repeated.getPayloadJson());
        payload.putObject("resolution").putObject("linkImportedList").put("targetListId", other.getId().toString());
        repeated.setPayloadJson(mapper.writeValueAsString(payload));
        items.saveAndFlush(repeated);

        assertThatThrownBy(() -> apply(retry, repeated))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.BadRequestException.class);
        assertThat(userLists.findByUserId(owner.getId())).hasSize(2);
    }

    private void setExternalIdentity(ImportItem item, String sourceId) throws Exception {
        var payload = (tools.jackson.databind.node.ObjectNode) mapper.readTree(item.getPayloadJson());
        var identity = payload.putObject("resolution").putObject("listIdentity");
        identity.put("namespace", "LETTERBOXD:ACCOUNT:letterboxd-test-account");
        identity.put("objectKind", "list_metadata");
        identity.put("sourceId", sourceId);
        item.setPayloadJson(mapper.writeValueAsString(payload));
        items.saveAndFlush(item);
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

    private ImportJob createJob(User owner, ImportInputKind inputKind) {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        return jobs.saveAndFlush(ImportJob.builder().userId(owner.getId())
                .inputKind(inputKind).sourceFormat(inputKind == ImportInputKind.PLATFORM_ARCHIVE
                        ? ImportSourceFormat.LETTERBOXD_ZIP : ImportSourceFormat.LETTERBOXD_CSV)
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
