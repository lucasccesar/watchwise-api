package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.dto.ImportJobDTO;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportListInventory;
import com.watchwise.watchwise_api.platformimport.model.ImportCategoryInventory;
import com.watchwise.watchwise_api.platformimport.model.ImportInventory;
import com.watchwise.watchwise_api.platformimport.model.ImportModeKey;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSelection;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.LinkReservation;
import com.watchwise.watchwise_api.platformimport.model.AliasQuery;
import com.watchwise.watchwise_api.platformimport.model.AliasResolution;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.repository.ImportFileCleanupTaskRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportJobService;
import com.watchwise.watchwise_api.platformimport.service.ImportModeHandler;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportCleanupJob;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportWorker;
import com.watchwise.watchwise_api.platformimport.storage.ImportFileStore;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.user.dto.DeleteAccountDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "app.import.worker.enabled=false",
        "app.import.cleanup.enabled=false",
        "app.import.max-pending-jobs-per-user=5",
        "app.import.max-pending-upload-bytes-per-user=100",
        "app.import.max-pending-upload-bytes-global=1000"
})
@Testcontainers
@Import(ImportWorkerIntegrationTest.TestApplierConfiguration.class)
class ImportWorkerIntegrationTest {

    private static final String TEST_FACTS_TABLE = "a7_import_test_facts";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.import.storage-directory", () -> java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "watchwise-import-worker-tests").toAbsolutePath().toString());
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private ImportItemRepository itemRepository;
    @Autowired private ImportFileCleanupTaskRepository cleanupTaskRepository;
    @Autowired private ImportFileStore fileStore;
    @Autowired private ImportLedger ledger;
    @Autowired private com.watchwise.watchwise_api.platformimport.service.ImportUploadQuotaService uploadQuotaService;
    @Autowired private ImportWorker worker;
    @Autowired private ImportCleanupJob cleanupJob;
    @Autowired private ImportJobService jobService;
    @Autowired private UserService userService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private Clock clock;
    @Autowired private TestImportUnitApplier testApplier;
    @Autowired private TestAliasService testAliasService;

    private final Set<String> storageKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + TEST_FACTS_TABLE + " (owner_id UUID NOT NULL, source_id VARCHAR(1000) NOT NULL UNIQUE)");
        jdbc.execute("TRUNCATE " + TEST_FACTS_TABLE);
        jdbc.execute("TRUNCATE import_items, external_import_links, external_content_aliases, imported_watch_assertions, import_deferred_records, letterboxd_seed_loads, import_file_cleanup_tasks, import_jobs CASCADE");
        testApplier.reset();
        testAliasService.reset();
        for (String storageKey : storageKeys) {
            try {
                fileStore.delete(storageKey);
            } catch (IOException ignored) {
            }
        }
        storageKeys.clear();
    }

    @Test
    @DisplayName("[ImportWorker] Should Resume After A Unit Failure Without Reapplying Committed Work")
    void shouldResumeAfterAUnitFailureWithoutReapplyingCommittedWork() {
        User owner = createUser();
        ImportJob job = createRunnableJob(owner.getId(), 2);
        List<ImportItem> items = createItems(job, 2, "crash");
        testApplier.crashOnceOn(items.get(1).getSourceId());

        assertThatThrownBy(() -> worker.runAvailable(1)).isInstanceOf(SimulatedWorkerCrash.class);

        assertThat(factCount(owner.getId())).isEqualTo(1);
        ImportJob afterCrash = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(afterCrash.getCheckpoint()).isEqualTo("0");
        assertThat(itemRepository.findById(items.get(0).getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        assertThat(itemRepository.findById(items.get(1).getId()).orElseThrow().getOutcome()).isNull();

        jdbc.update("UPDATE import_jobs SET lease_until = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(1), job.getId());
        assertThatCode(() -> worker.runAvailable(1)).doesNotThrowAnyException();

        assertThat(factCount(owner.getId())).isEqualTo(2);
        assertThat(testApplier.invocations(items.get(0).getSourceId())).isEqualTo(1);
        assertThat(testApplier.invocations(items.get(1).getSourceId())).isEqualTo(2);
        ImportJob completed = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(completed.getState()).isEqualTo(ImportJobState.COMPLETED);
        assertThat(completed.isWorkPending()).isFalse();
        assertThat(completed.getCheckpoint()).isEqualTo("1");
    }

    @Test
    @DisplayName("[ImportWorker] Should Not Claim The Same Job Twice - When Two Workers Run Concurrently")
    void shouldAllowConcurrentWorkersToClaimDifferentJobs() throws Exception {
        User firstOwner = createUser();
        User secondOwner = createUser();
        ImportJob firstJob = createRunnableJob(firstOwner.getId(), 1);
        ImportJob secondJob = createRunnableJob(secondOwner.getId(), 1);
        ImportItem firstItem = createItems(firstJob, 1, "blocked-worker").getFirst();
        ImportItem secondItem = createItems(secondJob, 1, "second-worker").getFirst();
        testApplier.blockOnceOn(firstItem.getSourceId());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> firstRun = executor.submit(() -> worker.runAvailable(1));
            assertThat(testApplier.awaitBlockedUnit()).isTrue();

            Future<Integer> secondRun = executor.submit(() -> worker.runAvailable(1));
            assertThat(secondRun.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(testApplier.invocations(secondItem.getSourceId())).isEqualTo(1);
            assertThat(testApplier.invocations(firstItem.getSourceId())).isEqualTo(1);
            assertThat(jobRepository.findById(secondJob.getId()).orElseThrow().getState())
                    .isEqualTo(ImportJobState.COMPLETED);

            testApplier.releaseBlockedUnit();
            assertThat(firstRun.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jobRepository.findById(firstJob.getId()).orElseThrow().getState())
                    .isEqualTo(ImportJobState.COMPLETED);
            assertThat(factCount(firstOwner.getId())).isEqualTo(1);
            assertThat(factCount(secondOwner.getId())).isEqualTo(1);
        } finally {
            testApplier.releaseBlockedUnit();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("[ImportWorker] Should Stop Between Bounded Chunks - When The Owner Cancels")
    void shouldStopBetweenChunksWhenOwnerCancels() {
        User owner = createUser();
        ImportJob job = createRunnableJob(owner.getId(), 201);
        createItems(job, 201, "cancel");

        assertThat(worker.runAvailable(1)).isEqualTo(1);
        assertThat(factCount(owner.getId())).isEqualTo(200);

        jobService.cancel(owner.getId(), job.getId());
        worker.runAvailable(1);

        ImportJob cancelled = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(cancelled.getState()).isEqualTo(ImportJobState.CANCELLED);
        assertThat(cancelled.isWorkPending()).isFalse();
        assertThat(factCount(owner.getId())).isEqualTo(200);
    }

    @Test
    @DisplayName("[ImportLedger] Should Reuse And Transfer The Original Reservation - When Its Job Was Cancelled")
    void shouldTransferCancelledReservationToTheResumedJob() {
        User owner = createUser();
        ImportJob abandonedJob = createRunnableJob(owner.getId(), 1);
        abandonedJob.setState(ImportJobState.CANCELLED);
        abandonedJob.setWorkPending(false);
        abandonedJob.setNextRunAt(null);
        jobRepository.saveAndFlush(abandonedJob);
        ImportSourceKey key = new ImportSourceKey("trakt", "diary_event", "shared-event");
        LinkReservation firstReservation = ledger.reserve(owner.getId(), abandonedJob.getId(), key, "first-hash");

        ImportJob retryJob = createRunnableJob(owner.getId(), 1);
        ImportItem item = item(retryJob, 0, key.namespace(), key.objectKind(), key.sourceId(), "{\"source\":\"retry\"}");
        itemRepository.saveAndFlush(item);
        worker.runAvailable(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_import_links WHERE user_id = ?",
                Integer.class, owner.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM external_import_links WHERE user_id = ?",
                UUID.class, owner.getId())).isEqualTo(firstReservation.linkId());
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM external_import_links WHERE user_id = ?",
                String.class, owner.getId())).isEqualTo("first-hash");
        assertThat(jdbc.queryForObject("SELECT owning_job_id FROM external_import_links WHERE user_id = ?",
                UUID.class, owner.getId())).isEqualTo(retryJob.getId());
        assertThat(factCount(owner.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("[ImportWorker] Should Reuse A Staged Resolution Across Pages - When A Query Repeats Later")
    void shouldReuseAStagedResolutionAcrossPagesWhenAQueryRepeatsLater() {
        User owner = createUser();
        ImportJob job = createRunnableJob(owner.getId(), 201);
        job.setState(ImportJobState.RESOLVING);
        job.setWorkPhase("RESOLVE");
        jobRepository.saveAndFlush(job);
        List<ImportItem> items = new ArrayList<>(201);
        for (int ordinal = 0; ordinal < 201; ordinal++) {
            String title = ordinal == 200 ? "Unique Film 0" : "Unique Film " + ordinal;
            items.add(item(job, ordinal, "letterboxd", "film", "same-query-" + ordinal,
                    "{\"query\":{\"kind\":\"CONTENT\",\"title\":\"" + title
                            + "\",\"year\":2001,\"contentType\":\"MOVIE\"}}"));
        }
        List<ImportItem> savedItems = itemRepository.saveAllAndFlush(items);

        assertThat(worker.runAvailable(1)).isEqualTo(1);

        assertThat(testAliasService.batchSizes()).containsExactly(200);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.READY);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().isWorkPending()).isFalse();
        assertThat(itemRepository.findById(savedItems.get(0).getId()).orElseThrow().getOutcome()).isEqualTo("MATCHED");
        assertThat(itemRepository.findById(savedItems.get(200).getId()).orElseThrow().getOutcome()).isEqualTo("MATCHED");
        assertThat(itemRepository.findById(savedItems.get(200).getId()).orElseThrow().getNormalizedContentKey())
                .isEqualTo(itemRepository.findById(savedItems.get(0).getId()).orElseThrow().getNormalizedContentKey());
    }

    @Test
    @DisplayName("[ImportWorker] Should Publish Inspection Inventory - When Archive Analysis Finishes")
    void shouldPublishInspectionInventoryWhenArchiveAnalysisFinishes() {
        User owner = createUser();
        ImportJob job = createInspectionJob(owner.getId());

        worker.runAvailable(1);

        ImportJobDTO response = jobService.get(owner.getId(), job.getId());
        assertThat(response.state()).isEqualTo(ImportJobState.AWAITING_SELECTION);
        assertThat(response.inventory()).isEqualTo(new ImportInventory(
                List.of(new ImportCategoryInventory(ImportRecordKind.DIARY_EVENT, 7, true)),
                List.of(new ImportListInventory("source-list", "Favorites", "Films to keep", 3, "COMPLETE"))));
    }

    @Test
    @DisplayName("[ImportCleanupJob] Should Queue And Delete Private Files - When An Owner Deletes Their Account")
    void shouldQueueAndDeletePrivateFilesWhenOwnerDeletesTheirAccount() throws Exception {
        User owner = createUser();
        String storageKey = storeFile("owner-delete-bytes");
        ImportJob job = createStoredJob(owner.getId(), storageKey, 18, LocalDateTime.now().plusDays(1));
        createItems(job, 1, "owner-delete");

        userService.deleteAccount(owner.getId(), new DeleteAccountDTO("Password123"));

        assertThat(userRepository.existsById(owner.getId())).isFalse();
        assertThat(jobRepository.existsById(job.getId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_items WHERE job_id = ?", Integer.class, job.getId()))
                .isZero();
        assertThat(cleanupTaskRepository.count()).isEqualTo(1);

        cleanupJob.runOnce(Instant.now());

        assertThat(cleanupTaskRepository.count()).isZero();
        assertFileMissing(storageKey);
    }

    @Test
    @DisplayName("[ImportCleanupJob] Should Delete Expired Bytes - While Preserving Deferred Payload")
    void shouldDeleteExpiredBytesWhilePreservingDeferredPayload() throws Exception {
        User owner = createUser();
        String storageKey = storeFile("expired-zip-bytes");
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiredAt = now.minusDays(1);
        ImportJob job = createStoredJob(owner.getId(), storageKey, 17, expiredAt);
        ImportItem pending = item(job, 0, "letterboxd", "diary_event", "deferred-item", "{\"review\":\"keep this\"}");
        itemRepository.saveAndFlush(pending);
        jdbc.update("""
                INSERT INTO import_deferred_records(id, user_id, source_namespace, object_kind, source_id,
                    normalized_payload, provenance, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), owner.getId(), "letterboxd", "diary_event", "deferred-item",
                "{\"review\":\"keep this\"}", "import job " + job.getId());

        job.setLeaseToken(UUID.randomUUID());
        job.setLeaseUntil(now.plusMinutes(2));
        jobRepository.saveAndFlush(job);
        cleanupJob.runOnce(Instant.now(clock));
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getUploadSizeBytes()).isEqualTo(17);
        assertFileExists(storageKey);

        job = jobRepository.findById(job.getId()).orElseThrow();
        job.setLeaseUntil(LocalDateTime.now(clock).minusSeconds(1));
        jobRepository.saveAndFlush(job);

        cleanupJob.runOnce(Instant.now(clock));

        ImportJob cleaned = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(cleaned.getState()).isEqualTo(ImportJobState.EXPIRED);
        assertThat(cleaned.getUploadSizeBytes()).isZero();
        assertFileMissing(storageKey);
        assertThat(itemRepository.findById(pending.getId()).orElseThrow().getPayloadJson())
                .isEqualTo("{\"review\":\"keep this\"}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_deferred_records WHERE user_id = ?",
                Integer.class, owner.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("[ImportUploadQuotaService] Should Count Failed File Bytes And Not Double Count Retry Reservation")
    void shouldCountFailedFileBytesAndNotDoubleCountRetryReservation() throws Exception {
        User owner = createUser();
        String storageKey = storeFile("retained-failed-upload");
        ImportJob failedJob = createStoredJob(owner.getId(), storageKey, 80, LocalDateTime.now().plusDays(1));
        failedJob.setState(ImportJobState.FAILED);
        failedJob.setWorkPhase("APPLY");
        jobRepository.saveAndFlush(failedJob);

        assertThatThrownBy(() -> uploadQuotaService.reserve(owner.getId(), ImportInputKind.SINGLE_LIST,
                ImportSourceFormat.TRAKT_JSON, ImportPlatform.TRAKT, null, "UTC",
                UUID.randomUUID().toString(), 30))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.TooManyRequestsException.class);

        uploadQuotaService.reactivate(owner.getId(), failedJob.getId());

        ImportJob retried = jobRepository.findById(failedJob.getId()).orElseThrow();
        assertThat(retried.getState()).isEqualTo(ImportJobState.IMPORTING);
        assertThat(retried.getUploadSizeBytes()).isEqualTo(80);
        assertThat(retried.getPreviewVersion()).isEqualTo(2);
    }

    private User createUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        LocalDateTime now = LocalDateTime.now();
        return userRepository.saveAndFlush(User.builder()
                .username("a7" + suffix)
                .name("Import Worker " + suffix)
                .email("a7" + suffix + "@example.com")
                .password(passwordEncoder.encode("Password123"))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private ImportJob createRunnableJob(UUID ownerId, int itemCount) {
        LocalDateTime now = LocalDateTime.now();
        ImportJob job = ImportJob.builder()
                .userId(ownerId)
                .inputKind(ImportInputKind.SINGLE_LIST)
                .sourceFormat(ImportSourceFormat.TRAKT_JSON)
                .platform(ImportPlatform.TRAKT)
                .sourceNamespace("trakt:test-account")
                .state(ImportJobState.IMPORTING)
                .previewVersion(1)
                .selectionJson("{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{}}")
                .timezone("UTC")
                .storageKey(UUID.randomUUID().toString())
                .uploadSizeBytes(0)
                .workPhase("APPLY")
                .workPending(true)
                .nextRunAt(now.minusSeconds(1))
                .createdAt(now.minusSeconds(itemCount))
                .updatedAt(now)
                .expiresAt(now.plusDays(1))
                .build();
        return jobRepository.saveAndFlush(job);
    }

    private ImportJob createStoredJob(UUID ownerId, String storageKey, long bytes, LocalDateTime expiresAt) {
        LocalDateTime now = LocalDateTime.now();
        return jobRepository.saveAndFlush(ImportJob.builder()
                .userId(ownerId)
                .inputKind(ImportInputKind.SINGLE_LIST)
                .sourceFormat(ImportSourceFormat.TRAKT_JSON)
                .platform(ImportPlatform.TRAKT)
                .sourceNamespace("trakt:test-account")
                .state(ImportJobState.READY)
                .previewVersion(1)
                .selectionJson("{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{}}")
                .timezone("UTC")
                .storageKey(storageKey)
                .uploadSizeBytes(bytes)
                .workPhase("APPLY")
                .workPending(false)
                .createdAt(now.minusDays(2))
                .updatedAt(now.minusDays(2))
                .expiresAt(expiresAt)
                .build());
    }

    private ImportJob createInspectionJob(UUID ownerId) {
        LocalDateTime now = LocalDateTime.now();
        return jobRepository.saveAndFlush(ImportJob.builder()
                .userId(ownerId)
                .inputKind(ImportInputKind.PLATFORM_ARCHIVE)
                .sourceFormat(ImportSourceFormat.TRAKT_ZIP)
                .platform(ImportPlatform.TRAKT)
                .sourceNamespace("trakt:test-account")
                .state(ImportJobState.ANALYZING)
                .previewVersion(0)
                .selectionJson("{}")
                .timezone("UTC")
                .storageKey(UUID.randomUUID().toString())
                .uploadSizeBytes(8)
                .workPhase("INSPECT")
                .workPending(true)
                .nextRunAt(now.minusSeconds(1))
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plusDays(1))
                .build());
    }

    private List<ImportItem> createItems(ImportJob job, int count, String prefix) {
        List<ImportItem> items = new ArrayList<>(count);
        for (int ordinal = 0; ordinal < count; ordinal++) {
            items.add(item(job, ordinal, "trakt", "diary_event", prefix + "-event-" + ordinal,
                    "{\"source\":\"" + prefix + "\"}"));
        }
        return itemRepository.saveAllAndFlush(items);
    }

    private ImportItem item(ImportJob job, int ordinal, String namespace, String objectKind,
                            String sourceId, String payload) {
        return ImportItem.builder()
                .jobId(job.getId())
                .planGeneration(1)
                .normalizedKind(ImportRecordKind.DIARY_EVENT)
                .sourceNamespace(namespace)
                .objectKind(objectKind)
                .sourceId(sourceId)
                .payloadJson(payload)
                .ordinal(ordinal)
                .build();
    }

    private long factCount(UUID ownerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + TEST_FACTS_TABLE + " WHERE owner_id = ?",
                Long.class, ownerId);
    }

    private String storeFile(String content) throws IOException {
        String key = UUID.randomUUID().toString();
        storageKeys.add(key);
        fileStore.store(new ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, key);
        return key;
    }

    private void assertFileMissing(String storageKey) {
        assertThatThrownBy(() -> fileStore.open(storageKey))
                .isInstanceOfAny(IOException.class, BadRequestException.class);
    }

    private void assertFileExists(String storageKey) throws IOException {
        try (var stream = fileStore.open(storageKey)) {
            assertThat(stream.read()).isNotEqualTo(-1);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestApplierConfiguration {
        @Bean
        TestImportUnitApplier testImportUnitApplier(JdbcTemplate jdbc, ImportItemRepository itemRepository,
                                                   ImportLedger ledger) {
            return new TestImportUnitApplier(jdbc, itemRepository, ledger);
        }

        @Bean
        @org.springframework.context.annotation.Primary
        TestAliasService testAliasService() {
            return new TestAliasService();
        }

        @Bean
        ImportModeHandler testArchiveHandler() {
            return new ImportModeHandler() {
                @Override
                public ImportModeKey mode() {
                    return new ImportModeKey(ImportInputKind.PLATFORM_ARCHIVE, ImportSourceFormat.TRAKT_ZIP);
                }

                @Override
                public ImportInventory inspect(ImportReadContext context) {
                    return new ImportInventory(
                            List.of(new ImportCategoryInventory(ImportRecordKind.DIARY_EVENT, 7, true)),
                            List.of(new ImportListInventory("source-list", "Favorites", "Films to keep", 3, "COMPLETE")));
                }

                @Override
                public void normalize(ImportReadContext context, ImportSelection selection,
                                      Consumer<NormalizedImportRecord> sink) {
                }
            };
        }
    }

    static final class TestImportUnitApplier implements ImportUnitApplier {
        private final JdbcTemplate jdbc;
        private final ImportItemRepository itemRepository;
        private final ImportLedger ledger;
        private final java.util.concurrent.ConcurrentMap<String, AtomicInteger> attempts =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicReference<String> crashSourceId = new AtomicReference<>();
        private final AtomicReference<String> blockSourceId = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> blockedUnitEntered =
                new AtomicReference<>(new CountDownLatch(1));
        private final AtomicReference<CountDownLatch> releaseBlockedUnit =
                new AtomicReference<>(new CountDownLatch(1));

        TestImportUnitApplier(JdbcTemplate jdbc, ImportItemRepository itemRepository, ImportLedger ledger) {
            this.jdbc = jdbc;
            this.itemRepository = itemRepository;
            this.ledger = ledger;
        }

        @Override
        public boolean supports(ImportRecordKind kind) {
            return kind == ImportRecordKind.DIARY_EVENT;
        }

        @Override
        public void apply(ImportExecutionContext context, UUID unitId) {
            ImportItem item = itemRepository.findById(unitId).orElseThrow();
            attempts.computeIfAbsent(item.getSourceId(), ignored -> new AtomicInteger()).incrementAndGet();

            String crashId = crashSourceId.get();
            if (item.getSourceId().equals(crashId) && crashSourceId.compareAndSet(crashId, null)) {
                throw new SimulatedWorkerCrash();
            }
            String blockedId = blockSourceId.get();
            if (item.getSourceId().equals(blockedId) && blockSourceId.compareAndSet(blockedId, null)) {
                blockedUnitEntered.get().countDown();
                await(releaseBlockedUnit.get());
            }

            ImportSourceKey sourceKey = new ImportSourceKey(
                    item.getSourceNamespace(), item.getObjectKind(), item.getSourceId());
            ledger.reserve(context.ownerId(), context.jobId(), sourceKey, "test-payload-hash");
            jdbc.update("INSERT INTO " + TEST_FACTS_TABLE + " (owner_id, source_id) VALUES (?, ?)",
                    context.ownerId(), item.getSourceId());
            ledger.markApplied(context.ownerId(), sourceKey, "TEST_FACT", UUID.nameUUIDFromBytes(
                    item.getSourceId().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        void reset() {
            attempts.clear();
            crashSourceId.set(null);
            blockSourceId.set(null);
            blockedUnitEntered.set(new CountDownLatch(1));
            releaseBlockedUnit.set(new CountDownLatch(1));
        }

        void crashOnceOn(String sourceId) {
            crashSourceId.set(sourceId);
        }

        void blockOnceOn(String sourceId) {
            blockSourceId.set(sourceId);
        }

        boolean awaitBlockedUnit() throws InterruptedException {
            return blockedUnitEntered.get().await(10, TimeUnit.SECONDS);
        }

        void releaseBlockedUnit() {
            releaseBlockedUnit.get().countDown();
        }

        int invocations(String sourceId) {
            AtomicInteger count = attempts.get(sourceId);
            return count == null ? 0 : count.get();
        }

        private void await(CountDownLatch latch) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release the test applier");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting in the test applier", exception);
            }
        }
    }

    static final class TestAliasService implements ExternalContentAliasService {
        private final java.util.List<Integer> batchSizes = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final ImportTarget target = new ImportTarget.ContentTarget(MediaReference.movie("603"));

        @Override
        public AliasResolution resolve(UUID owner, AliasQuery query) {
            return AliasResolution.matched(target);
        }

        @Override
        public List<AliasResolution> resolveBatch(UUID owner, List<AliasQuery> queries) {
            batchSizes.add(queries.size());
            return queries.stream().map(ignored -> AliasResolution.matched(target)).toList();
        }

        @Override
        public void remember(UUID owner, AliasQuery query, ImportTarget target,
                             com.watchwise.watchwise_api.platformimport.model.AliasTrust trust) {
        }

        void reset() {
            batchSizes.clear();
        }

        List<Integer> batchSizes() {
            return List.copyOf(batchSizes);
        }
    }

    private static final class SimulatedWorkerCrash extends Error {
    }
}
