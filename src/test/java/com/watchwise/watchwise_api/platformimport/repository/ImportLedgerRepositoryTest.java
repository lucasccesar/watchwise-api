package com.watchwise.watchwise_api.platformimport.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.BeforeEach;
import com.watchwise.watchwise_api.platformimport.model.ImportLinkState;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.LinkReservation;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportLedgerImpl;
import com.watchwise.watchwise_api.platformimport.entity.ExternalImportLink;
import com.watchwise.watchwise_api.platformimport.repository.ExternalImportLinkRepository;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Instant;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ImportLedgerImpl.class)
class ImportLedgerRepositoryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ImportLedger ledger;
    @Autowired ExternalImportLinkRepository externalImportLinkRepository;

    @BeforeEach
    void clearImportData() {
        jdbc.execute("TRUNCATE import_items, external_import_links, external_content_aliases, imported_watch_assertions, import_deferred_records, letterboxd_seed_loads, import_file_cleanup_tasks, import_jobs CASCADE");
    }

    @Test
    @DisplayName("[external_import_links] Should Enforce One Reservation - When Source Identity Repeats")
    void shouldEnforceOneReservationWhenSourceIdentityRepeats() {
        UUID user = user("a2-owner");
        UUID firstJob = job(user);
        UUID secondJob = job(user);
        ImportSourceKey key = new ImportSourceKey("trakt", "diary_event", "same-source");
        LinkReservation first = ledger.reserve(user, firstJob, key, "hash-one");
        LinkReservation duplicate = ledger.reserve(user, secondJob, key, "hash-two");
        assertThat(jdbc.queryForObject("select count(*) from external_import_links where user_id = ?", Integer.class, user)).isEqualTo(1);
        assertThat(duplicate).isEqualTo(first);
        assertThat(duplicate.owningJobId()).isEqualTo(firstJob);
    }

    @Test
    @DisplayName("[ImportLedger.reserve] Should Reject A Foreign Job For An Existing Link - When Source Identity Already Exists")
    void shouldRejectAForeignJobForAnExistingLinkWhenSourceIdentityAlreadyExists() {
        UUID owner = user("a2-existing-link-owner");
        UUID otherOwner = user("a2-existing-link-other");
        UUID ownerJob = job(owner);
        UUID otherJob = job(otherOwner);
        ImportSourceKey key = new ImportSourceKey("trakt", "diary_event", "existing-link-owner-check");
        LinkReservation original = ledger.reserve(owner, ownerJob, key, "hash");

        assertThatThrownBy(() -> ledger.reserve(owner, otherJob, key, "hash"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ledger.reserve(owner, ownerJob, key, "hash")).isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from external_import_links where user_id = ? and source_id = ?", Integer.class, owner, key.sourceId())).isEqualTo(1);
    }

    @Test
    @DisplayName("[external_import_links] Should Keep Transaction Usable - When Reservations Race")
    void shouldKeepTransactionUsableWhenReservationsRace() {
        UUID owner = user("a2-race");
        UUID job = job(owner);
        ImportSourceKey key = new ImportSourceKey("trakt", "diary_event", "racing-source");
        CountDownLatch firstReserved = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarting = new CountDownLatch(1);
        AtomicLong secondPid = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<LinkReservation> first = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                LinkReservation value = ledger.reserve(owner, job, key, "race-hash");
                firstReserved.countDown();
                await(releaseFirst);
                return value;
            }));
            assertThat(firstReserved.await(5, TimeUnit.SECONDS)).isTrue();
            Future<LinkReservation> second = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                secondPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class).longValue());
                secondStarting.countDown();
                LinkReservation value = ledger.reserve(owner, job, key, "race-hash");
                assertThat(jdbc.queryForObject("select count(*) from external_import_links where user_id = ?", Integer.class, owner)).isEqualTo(1);
                return value;
            }));
            assertThat(secondStarting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitLockWait(secondPid.get())).isTrue();
            releaseFirst.countDown();
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(first.get(10, TimeUnit.SECONDS));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("[external_content_aliases] Should Allow Distinct Targets And Constrain Duplicate Candidates - When Titles Match")
    void shouldAllowDistinctTargetsAndConstrainDuplicateCandidatesWhenTitlesMatch() {
        insertAlias(MediaReference.movie("1001"), null, "Alias Movie", "alias movie", "GLOBAL");
        insertAlias(MediaReference.movie("1002"), null, "Alias Movie", "alias movie", "GLOBAL");
        insertAlias(MediaReference.movie("1001"), null, "Alias Movie", "alias movie", "GLOBAL");
        insertAlias(MediaReference.series("2001"), null, "Alias Series", "alias series", "GLOBAL");
        insertAlias(MediaReference.season("3001", 2), null, "Alias Season", "alias season", "GLOBAL");
        insertSeasonZeroAlias();
        insertAlias(MediaReference.episode("3001", 2, 3), null, "Alias Episode", "alias episode", "GLOBAL");
        insertPersonAlias("Person Alias", "person alias", "42");
        assertThat(jdbc.queryForObject("select count(*) from external_content_aliases", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("select target_key from external_content_aliases where target_type = 'SEASON' and season_number = 0", String.class))
                .isEqualTo("CONTENT|SEASON|3001|0");
        assertThat(jdbc.queryForObject("select count(*) from contents where tmdb_id in ('1001', '1002')", Integer.class)).isZero();
    }

    @Test
    @DisplayName("[external_import_links] Should Isolate Source Identity - When Owners Differ")
    void shouldIsolateSourceIdentityWhenOwnersDiffer() {
        UUID firstUser = user("a2-owner-one");
        UUID secondUser = user("a2-owner-two");
        assertThat(insertLink(UUID.randomUUID(), firstUser, job(firstUser))).isTrue();
        assertThat(insertLink(UUID.randomUUID(), secondUser, job(secondUser))).isTrue();
    }

    @Test
    @DisplayName("[ImportLedger.reserve] Should Reject A Job Owned By Another User - When Creating A New Link")
    void shouldRejectAJobOwnedByAnotherUserWhenCreatingANewLink() {
        UUID owner = user("a2-reservation-owner");
        UUID otherOwner = user("a2-reservation-other");
        UUID otherJob = job(otherOwner);
        ImportSourceKey key = new ImportSourceKey("trakt", "diary_event", "wrong-job-owner");
        assertThatThrownBy(() -> ledger.reserve(owner, otherJob, key, "hash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from external_import_links where source_id = ?", Integer.class, key.sourceId())).isZero();
    }

    @Test
    @DisplayName("[uq_import_jobs_one_writer_per_user] Should Permit Only One Importing Job - When User Has Concurrent Jobs")
    void shouldPermitOnlyOneImportingJobWhenUserHasConcurrentJobs() {
        UUID owner = user("a2-single-writer");
        UUID activeJob = job(owner);
        jdbc.update("update import_jobs set state = 'IMPORTING' where id = ?", activeJob);
        UUID anotherJob = job(owner);
        assertThatThrownBy(() -> jdbc.update("update import_jobs set state = 'IMPORTING' where id = ?", anotherJob))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[import_jobs] Should Allow Null Platform Only For Watchwise Template - When Creating Job")
    void shouldAllowNullPlatformOnlyForWatchwiseTemplateWhenCreatingJob() {
        UUID owner = user("a2-platform-template");
        UUID templateJob = UUID.randomUUID();
        insertJob(templateJob, owner, "PICKS_TEMPLATE", "WATCHWISE_TEMPLATE_JSON", null);
        assertThat(jdbc.queryForObject("select platform from import_jobs where id = ?", String.class, templateJob)).isNull();

        insertJob(UUID.randomUUID(), owner, "PLATFORM_ARCHIVE", "TRAKT_ZIP", "TRAKT");
        insertJob(UUID.randomUUID(), owner, "PLATFORM_ARCHIVE", "LETTERBOXD_ZIP", "LETTERBOXD");
        insertJob(UUID.randomUUID(), owner, "SINGLE_LIST", "TRAKT_JSON", "TRAKT");
        insertJob(UUID.randomUUID(), owner, "SINGLE_LIST", "LETTERBOXD_CSV", "LETTERBOXD");
        insertJob(UUID.randomUUID(), owner, "SINGLE_WATCHLIST", "TRAKT_JSON", "TRAKT");
        insertJob(UUID.randomUUID(), owner, "SINGLE_WATCHLIST", "LETTERBOXD_CSV", "LETTERBOXD");

        assertThatThrownBy(() -> insertJob(UUID.randomUUID(), owner, "PLATFORM_ARCHIVE", "TRAKT_ZIP", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertJob(UUID.randomUUID(), owner, "PLATFORM_ARCHIVE", "LETTERBOXD_ZIP", "TRAKT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[external_content_aliases] Should Reject Invalid Typed IDs And Natural Keys - When Alias Fields Disagree")
    void shouldRejectInvalidTypedIdsAndNaturalKeysWhenAliasFieldsDisagree() {
        assertRejectedAlias("CONTENT", "MOVIE", "CONTENT|MOVIE|2001", null, null, null, null, null);
        assertRejectedAlias("CONTENT", "SEASON", "CONTENT|SEASON|77|1", "2002", "77", 1, null, null);
        assertRejectedAlias("CONTENT", "EPISODE", "CONTENT|EPISODE|77|1|2", null, "77", 1, null, null);
        assertRejectedAlias("CONTENT", "MOVIE", "CONTENT|MOVIE|9999", "2003", null, null, null, null);
        assertRejectedAlias("PERSON", "PERSON", "PERSON|42", "99", null, null, null, "42");
        assertRejectedAlias("PERSON", "PERSON", "PERSON|99", null, null, null, null, "42");
    }

    @Test
    @DisplayName("[external_import_links.source_id] Should Persist More Than 255 Characters - When Using JPA Mapping")
    void shouldPersistMoreThan255CharactersWhenUsingJpaMapping() {
        UUID owner = user("a2-long-source");
        UUID job = job(owner);
        String sourceId = "x".repeat(300);
        ExternalImportLink saved = externalImportLinkRepository.saveAndFlush(ExternalImportLink.builder()
                .userId(owner).sourceNamespace("trakt").objectKind("diary_event").sourceId(sourceId)
                .state(ImportLinkState.IN_PROGRESS).owningJobId(job).createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now()).build());
        assertThat(jdbc.queryForObject("select source_id from external_import_links where id = ?", String.class, saved.getId()))
                .hasSize(300);
    }

    @Test
    @DisplayName("[account deletion] Should Cascade User Data And Preserve Cleanup Outbox - When User Is Deleted")
    void shouldCascadeUserDataAndPreserveCleanupOutboxWhenUserIsDeleted() {
        UUID owner = user("a2-delete");
        UUID jobId = job(owner);
        jdbc.update("insert into import_file_cleanup_tasks(id, storage_key, ready_at, created_at) values (?, ?, now(), now())", UUID.randomUUID(), "private/generated/key");
        jdbc.update("delete from users where id = ?", owner);
        assertThat(jdbc.queryForObject("select count(*) from import_jobs where id = ?", Integer.class, jobId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from import_file_cleanup_tasks", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("[external_import_links] Should Retain Tombstone - When Target Was Deleted")
    void shouldRetainTombstoneWhenTargetWasDeleted() {
        UUID owner = user("a2-tombstone");
        UUID jobId = job(owner);
        UUID linkId = UUID.randomUUID();
        insertLink(linkId, owner, jobId);
        UUID targetId = UUID.randomUUID();
        jdbc.update("update external_import_links set target_type = 'DIARY_ENTRY', target_id = ? where id = ?", targetId, linkId);
        ledger.markDeletedByTarget(owner, "DIARY_ENTRY", targetId);
        assertThat(jdbc.queryForObject("select state from external_import_links where id = ?", String.class, linkId)).isEqualTo("TOMBSTONE");
        LinkReservation reservation = ledger.reserve(owner, job(owner), new ImportSourceKey("trakt", "diary_event", "same-source"), "hash");
        assertThat(reservation.state()).isEqualTo(ImportLinkState.TOMBSTONE);
    }

    @Test
    @DisplayName("[ImportLedger.isConsumed] Should Exclude In Progress And Include Terminal States - When Link State Changes")
    void shouldExcludeInProgressAndIncludeTerminalStatesWhenLinkStateChanges() {
        UUID owner = user("a2-states"); UUID job = job(owner);
        ImportSourceKey key = new ImportSourceKey("trakt", "diary_event", "state-key");
        ledger.reserve(owner, job, key, "hash");
        assertThat(ledger.isConsumed(owner, key)).isFalse();
        ledger.markApplied(owner, key, "DIARY_ENTRY", UUID.randomUUID());
        assertThat(ledger.isConsumed(owner, key)).isTrue();
        UUID appliedTarget = jdbc.queryForObject("select target_id from external_import_links where source_id='state-key'", UUID.class);
        ledger.markApplied(owner, key, "DIARY_ENTRY", appliedTarget);
        assertThatThrownBy(() -> ledger.markApplied(owner, key, "DIARY_ENTRY", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.markSkipped(owner, key, "DIARY_ENTRY", appliedTarget, "conflict"))
                .isInstanceOf(IllegalStateException.class);
        ImportSourceKey skipped = new ImportSourceKey("trakt", "diary_event", "skipped-key");
        ledger.reserve(owner, job, skipped, "hash"); ledger.markSkipped(owner, skipped, "DIARY_ENTRY", UUID.randomUUID(), "user declined");
        assertThat(ledger.isConsumed(owner, skipped)).isTrue();
        UUID skippedTarget = jdbc.queryForObject("select target_id from external_import_links where source_id='skipped-key'", UUID.class);
        ledger.markSkipped(owner, skipped, "DIARY_ENTRY", skippedTarget, "user declined");
        assertThatThrownBy(() -> ledger.markApplied(owner, skipped, "DIARY_ENTRY", skippedTarget))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.markSkipped(owner, skipped, "DIARY_ENTRY", skippedTarget, "changed reason"))
                .isInstanceOf(IllegalStateException.class);
        ImportSourceKey missing = new ImportSourceKey("trakt", "diary_event", "not-reserved");
        assertThatThrownBy(() -> ledger.markApplied(owner, missing, "DIARY_ENTRY", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.markSkipped(owner, missing, "DIARY_ENTRY", UUID.randomUUID(), "missing"))
                .isInstanceOf(IllegalStateException.class);
        ImportSourceKey tombstone = new ImportSourceKey("trakt", "diary_event", "tombstone-key");
        ledger.reserve(owner, job, tombstone, "hash");
        UUID target = UUID.randomUUID();
        ledger.markApplied(owner, tombstone, "DIARY_ENTRY", target);
        ledger.markDeletedByTarget(owner, "DIARY_ENTRY", target);
        assertThat(ledger.isConsumed(owner, tombstone)).isTrue();
        assertThatThrownBy(() -> ledger.markApplied(owner, tombstone, "DIARY_ENTRY", target))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.markSkipped(owner, tombstone, "DIARY_ENTRY", target, "deleted"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("[imported_watch_assertions] Should Remain Unknown And Separate From Completion - When Episode Scope Is Unproven")
    void shouldRemainUnknownAndSeparateFromCompletionWhenEpisodeScopeIsUnproven() {
        UUID owner = user("a2-assertion");
        UUID contentId = content();
        jdbc.update("insert into imported_watch_assertions(id, user_id, content_id, source_namespace, object_kind, source_id, coverage, created_at) values (?, ?, ?, 'trakt', 'episode', 'episode-1', 'UNKNOWN', now())",
                UUID.randomUUID(), owner, contentId);
        assertThat(jdbc.queryForObject("select coverage from imported_watch_assertions where user_id = ?", String.class, owner)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select count(*) from diary_entries where user_id = ?", Integer.class, owner)).isZero();
    }

    private UUID user(String suffix) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users(id, username, name, email, password, profile_picture, created_at, updated_at) values (?, ?, ?, ?, 'x', 'x', now(), now())", id, suffix, suffix, suffix + "@test.local");
        return id;
    }

    private UUID job(UUID owner) {
        UUID id = UUID.randomUUID();
        insertJob(id, owner, "PLATFORM_ARCHIVE", "TRAKT_ZIP", "TRAKT");
        return id;
    }

    private void insertJob(UUID id, UUID owner, String inputKind, String sourceFormat, String platform) {
        jdbc.update("""
            insert into import_jobs(id, user_id, input_kind, source_format, platform, source_namespace, state, preview_version,
            selection_json, timezone, storage_key, work_pending, created_at, updated_at) values (?, ?, ?, ?, ?, 'trakt', 'READY', 1, '{}', 'UTC', ?, true, now(), now())""",
                id, owner, inputKind, sourceFormat, platform, "generated/" + id);
    }

    private boolean insertLink(UUID id, UUID owner, UUID job) {
        return jdbc.update("""
            insert into external_import_links(id, user_id, source_namespace, object_kind, source_id, state, owning_job_id, created_at, updated_at)
            values (?, ?, 'trakt', 'diary_event', 'same-source', 'IN_PROGRESS', ?, now(), now()) on conflict do nothing""", id, owner, job) == 1;
    }

    private void insertAlias(com.watchwise.watchwise_api.content.dto.MediaReference target, Integer year,
                             String originalTitle, String normalizedTitle, String scope) {
        String targetKey = targetKey(target);
        jdbc.update("""
            insert into external_content_aliases(id, scope_key, target_kind, original_title, normalized_title, release_year,
                target_key, target_type, target_tmdb_id, series_tmdb_id, season_number, episode_number,
                provenance, trust, created_at, updated_at)
            values (?, ?, 'CONTENT', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'seed', 'CURATED_SEED', now(), now()) on conflict do nothing
            """, UUID.randomUUID(), scope, originalTitle, normalizedTitle, year, targetKey, target.type().name(),
                target.tmdbId(), target.seriesTmdbId(), target.seasonNumber(), target.episodeNumber());
    }

    private void insertPersonAlias(String originalTitle, String normalizedTitle, String personTmdbId) {
        jdbc.update("""
            insert into external_content_aliases(id, scope_key, target_kind, original_title, normalized_title,
                target_key, target_type, person_tmdb_id, provenance, trust)
            values (?, 'GLOBAL', 'PERSON', ?, ?, ?, 'PERSON', ?, 'seed', 'CURATED_SEED')
            """, UUID.randomUUID(), originalTitle, normalizedTitle, "PERSON|" + personTmdbId, personTmdbId);
    }

    private void insertSeasonZeroAlias() {
        jdbc.update("""
            insert into external_content_aliases(id, scope_key, target_kind, original_title, normalized_title,
                target_key, target_type, series_tmdb_id, season_number, provenance, trust)
            values (?, 'GLOBAL', 'CONTENT', 'Specials', 'specials', 'CONTENT|SEASON|3001|0', 'SEASON', '3001', 0,
                'seed', 'CURATED_SEED')
            """, UUID.randomUUID());
    }

    private void assertRejectedAlias(String targetKind, String targetType, String targetKey, String tmdbId,
                                     String seriesId, Integer season, Integer episode, String personId) {
        assertThatThrownBy(() -> jdbc.update("""
            insert into external_content_aliases(id, scope_key, target_kind, original_title, normalized_title, target_key,
                target_type, target_tmdb_id, series_tmdb_id, season_number, episode_number, person_tmdb_id, provenance, trust)
            values (?, 'GLOBAL', ?, 'Malformed', 'malformed', ?, ?, ?, ?, ?, ?, ?, 'test', 'UNVERIFIED_CANDIDATE')
            """, UUID.randomUUID(), targetKind, targetKey, targetType, tmdbId, seriesId, season, episode, personId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String targetKey(com.watchwise.watchwise_api.content.dto.MediaReference reference) {
        return switch (reference.type()) {
            case MOVIE, SERIES -> "CONTENT|" + reference.type() + "|" + reference.tmdbId();
            case SEASON -> "CONTENT|SEASON|" + reference.seriesTmdbId() + "|" + reference.seasonNumber();
            case EPISODE -> "CONTENT|EPISODE|" + reference.seriesTmdbId() + "|" + reference.seasonNumber()
                    + "|" + reference.episodeNumber();
        };
    }

    private UUID content() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contents(id, tmdb_id, type, created_at, updated_at) values (?, 'a2-content', 'MOVIE', now(), now())", id);
        return id;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for transaction release");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private boolean awaitLockWait(long backendPid) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            Boolean waiting = jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid = ? and wait_event_type = 'Lock')", Boolean.class, backendPid);
            if (Boolean.TRUE.equals(waiting)) return true;
            try { Thread.sleep(10); } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
        return false;
    }
}
