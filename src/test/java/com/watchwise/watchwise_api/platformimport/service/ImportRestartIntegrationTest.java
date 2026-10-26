package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "app.import.worker.enabled=false",
        "app.import.cleanup.enabled=false"
})
@Testcontainers
class ImportRestartIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.import.storage-directory", () -> java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "watchwise-import-restart-tests").toAbsolutePath().toString());
    }

    @Autowired private ImportJobRepository jobs;
    @Autowired private ImportItemRepository items;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void clearImportState() {
        jdbc.execute("TRUNCATE import_items, external_import_links, external_content_aliases, imported_watch_assertions, import_deferred_records, import_file_cleanup_tasks, import_jobs CASCADE");
    }

    @Test
    void applyPhaseOrderSurvivesCommittedChunkAndRestart() {
        User owner = createUser();
        UUID jobId = createJob(owner.getId());
        items.saveAllAndFlush(List.of(
                item(jobId, owner.getId(), ImportRecordKind.WATCHLIST_ITEM, 6),
                item(jobId, owner.getId(), ImportRecordKind.LIST_ITEM, 5),
                item(jobId, owner.getId(), ImportRecordKind.LIST_METADATA, 3),
                item(jobId, owner.getId(), ImportRecordKind.CURRENT_RATING, 4),
                item(jobId, owner.getId(), ImportRecordKind.REVIEW, 1),
                item(jobId, owner.getId(), ImportRecordKind.WATCHED_SUMMARY, 0),
                item(jobId, owner.getId(), ImportRecordKind.WATCHED_MARKER, 9),
                item(jobId, owner.getId(), ImportRecordKind.DIARY_EVENT, 8),
                item(jobId, owner.getId(), ImportRecordKind.SERIES_EXPANSION, 2)));

        List<ImportItem> firstChunk = items.findApplyPending(jobId, 1, PageRequest.of(0, 3));
        assertThat(firstChunk).extracting(ImportItem::getNormalizedKind).containsExactly(
                ImportRecordKind.SERIES_EXPANSION, ImportRecordKind.DIARY_EVENT, ImportRecordKind.WATCHED_MARKER);

        firstChunk.forEach(item -> item.setOutcome("APPLIED"));
        items.saveAllAndFlush(firstChunk);
        entityManager.clear();

        List<ImportItem> afterRestart = items.findApplyPending(jobId, 1, PageRequest.of(0, 20));
        assertThat(afterRestart).extracting(ImportItem::getNormalizedKind).containsExactly(
                ImportRecordKind.WATCHED_SUMMARY,
                ImportRecordKind.REVIEW, ImportRecordKind.CURRENT_RATING,
                ImportRecordKind.LIST_METADATA, ImportRecordKind.LIST_ITEM, ImportRecordKind.WATCHLIST_ITEM);
    }

    private UUID createJob(UUID ownerId) {
        LocalDateTime now = LocalDateTime.now();
        return jobs.saveAndFlush(ImportJob.builder().userId(ownerId).inputKind(ImportInputKind.PLATFORM_ARCHIVE)
                .sourceFormat(ImportSourceFormat.TRAKT_ZIP).platform(ImportPlatform.TRAKT)
                .sourceNamespace("TRAKT:ACCOUNT:restart-test").sourceAccountId("restart-test")
                .state(ImportJobState.IMPORTING).previewVersion(1)
                .selectionJson("{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{\"history\":true,\"watchlist\":true,\"listIds\":[],\"metadata\":null}}")
                .timezone("UTC").storageKey(UUID.randomUUID().toString()).uploadSizeBytes(1)
                .workPhase("APPLY").workPending(true).createdAt(now).updatedAt(now).expiresAt(now.plusHours(1))
                .build()).getId();
    }

    private ImportItem item(UUID jobId, UUID ownerId, ImportRecordKind kind, long ordinal) {
        return ImportItem.builder().jobId(jobId).planGeneration(1).normalizedKind(kind)
                .sourceNamespace("TRAKT:ACCOUNT:restart-test").objectKind(kind.name().toLowerCase())
                .sourceId(kind.name().toLowerCase() + "-" + ordinal).sourceEventKey(kind.name() + ordinal)
                .parserVersion("test").payloadJson("{}")
                .resolvedTargetJson("{\"type\":\"content\",\"content\":{\"type\":\"MOVIE\",\"tmdbId\":\"1\"}}")
                .outcome("MATCHED").ordinal(ordinal).build();
    }

    private User createUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        LocalDateTime now = LocalDateTime.now();
        return users.saveAndFlush(User.builder().username("restart" + suffix).name("Restart " + suffix)
                .email("restart" + suffix + "@example.com").password("Password123")
                .createdAt(now).updatedAt(now).build());
    }
}
