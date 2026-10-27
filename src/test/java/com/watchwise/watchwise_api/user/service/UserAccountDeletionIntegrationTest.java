package com.watchwise.watchwise_api.user.service;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.like.repository.AccountLikeFixture;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.report.service.ReportReporterDeletionService;
import com.watchwise.watchwise_api.user.dto.DeleteAccountDTO;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@Testcontainers
class UserAccountDeletionIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }
    @Autowired UserAccountDeletionService deletion;
    @Autowired LikeService likes;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean LikeRepository likeRepository;
    @MockitoSpyBean UserRepository userRepository;
    @MockitoSpyBean ReportReporterDeletionService reporterDeletion;
    AccountLikeFixture fixture;
    UUID child, parent, reportCase, storageKey;

    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE users, contents, report_cases, import_file_cleanup_tasks CASCADE");
        fixture = new AccountLikeFixture(jdbc);
        fixture.seed();
        jdbc.update("UPDATE users SET password = ? WHERE id = ?", encoder.encode("Password123"), fixture.actor);
        child = UUID.randomUUID(); parent = UUID.randomUUID(); reportCase = UUID.randomUUID(); storageKey = UUID.randomUUID();
        jdbc.update("INSERT INTO user_lists(id,user_id,name,visibility,rank) VALUES (?,?,'Child','PUBLIC',1)", child, fixture.actor);
        jdbc.update("INSERT INTO user_lists(id,user_id,name,visibility,rank) VALUES (?,?,'Parent','PUBLIC',2)", parent, fixture.owner);
        jdbc.update("INSERT INTO user_list_items(id,user_list_id,child_list_id,position) VALUES (?,?,?,1)", UUID.randomUUID(), parent, child);
        jdbc.update("INSERT INTO user_list_items(id,user_list_id,content_id,position) VALUES (?,?,?,2)", UUID.randomUUID(), parent, fixture.content);
        jdbc.update("INSERT INTO report_cases(id,target_type,target_id,status,created_at,updated_at) VALUES (?,'USER',?,'OPEN',now(),now())", reportCase, fixture.owner);
        jdbc.update("INSERT INTO report_submissions(id,case_id,reporter_id,reason,target_snapshot,created_at) VALUES (?,?,?,'OTHER','{}',now())", UUID.randomUUID(), reportCase, fixture.actor);
        jdbc.update("INSERT INTO import_jobs(id,user_id,input_kind,source_format,platform,source_namespace,state,selection_json,timezone,storage_key,upload_size_bytes,zip_digest) VALUES (?,?,'PLATFORM_ARCHIVE','TRAKT_ZIP','TRAKT','fixture','READY','{}','UTC',?,1,'fixture-digest')",
                UUID.randomUUID(), fixture.actor, storageKey.toString());
    }

    void deleteAccount() { deletion.deleteAccount(fixture.actor, new DeleteAccountDTO("Password123")); }

    @Test void reconcilesSixSurvivingKindsRemovesSelfLikesAndPreservesOtherLikes() {
        fixture.addLike(fixture.actor, new AccountLikeFixture.Target("user_lists", "list_id", child));
        jdbc.update("UPDATE user_lists SET likes_count = 1 WHERE id = ?", child);
        // A template made by the actor survives the creator FK's SET NULL action.
        UUID ownTemplate = UUID.randomUUID();
        jdbc.update("INSERT INTO picks_templates(id,creator_id,origin,name,created_at,updated_at,likes_count) VALUES (?,?,'COMMUNITY','Own',now(),now(),1)", ownTemplate, fixture.actor);
        fixture.addLike(fixture.actor, new AccountLikeFixture.Target("picks_templates", "picks_template_id", ownTemplate));

        deleteAccount();

        fixture.assertCounts(1);
        assertThat(count("SELECT COUNT(*) FROM likes WHERE user_id = ?", fixture.actor)).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ?", fixture.actor)).isZero();
        assertThat(jdbc.queryForObject("SELECT likes_count FROM picks_templates WHERE id = ?", Integer.class, ownTemplate)).isZero();
        assertThat(jdbc.queryForObject("SELECT creator_id FROM picks_templates WHERE id = ?", UUID.class, ownTemplate)).isNull();
        assertThat(jdbc.queryForList("SELECT position FROM user_list_items WHERE user_list_id = ? ORDER BY position", Integer.class, parent)).containsExactly(1);
        assertThat(count("SELECT COUNT(*) FROM report_cases WHERE id = ?", reportCase)).isZero();
        assertThat(count("SELECT COUNT(*) FROM import_jobs WHERE user_id = ?", fixture.actor)).isZero();
        assertThat(count("SELECT COUNT(*) FROM import_file_cleanup_tasks WHERE storage_key = ?", storageKey.toString())).isEqualTo(1);
    }

    @Test void rollsBackCountersLikesListsQuotaAndReportsWhenLaterCleanupFails() {
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("after reporter cleanup"); })
                .when(reporterDeletion).removeReporterSubmissions(fixture.actor);
        assertThatThrownBy(this::deleteAccount).isInstanceOf(IllegalStateException.class);
        fixture.assertCounts(2);
        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ?", fixture.actor)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT position FROM user_list_items WHERE user_list_id = ? ORDER BY position", Integer.class, parent)).containsExactly(1, 2);
        assertThat(count("SELECT COUNT(*) FROM report_submissions WHERE reporter_id = ?", fixture.actor)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM import_jobs WHERE user_id = ?", fixture.actor)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM import_file_cleanup_tasks WHERE storage_key = ?", storageKey.toString())).isZero();
    }

    @Test void uncoveredCounterRollsBackExactDeletionRatherThanClamping() {
        var target = fixture.targets.get(1);
        jdbc.update("UPDATE comments SET likes_count = 0 WHERE id = ?", target.id());
        assertThatThrownBy(this::deleteAccount).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("SELECT COUNT(*) FROM likes WHERE user_id = ?", fixture.actor)).isEqualTo(6);
        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ?", fixture.actor)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT position FROM user_list_items WHERE user_list_id = ? ORDER BY position", Integer.class, parent)).containsExactly(1, 2);
    }

    @Test void survivingUnlikeSerializesWithAccountDeletionWithoutPartialState() throws Exception {
        raceAtCleanup(() -> likes.unlikeList(fixture.keeper, fixture.targets.getFirst().id()), fixture.keeper, false);
        assertThat(count("SELECT COUNT(*) FROM likes WHERE list_id = ?", fixture.targets.getFirst().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT likes_count FROM user_lists WHERE id = ?", Integer.class, fixture.targets.getFirst().id())).isZero();
        for (var target : fixture.targets.subList(1, 6)) {
            assertThat(jdbc.queryForObject("SELECT likes_count FROM " + target.table() + " WHERE id = ?", Integer.class, target.id())).isEqualTo(1);
        }
    }

    @Test void deletingActorCannotCreateLikeAfterDeletionCapturedTargets() throws Exception {
        raceAtCleanup(() -> likes.likeList(fixture.actor, fixture.targets.getFirst().id()), fixture.actor, true);
        fixture.assertCounts(1);
    }

    @Test void survivingLikeSerializesWithAccountDeletionWithoutLosingIncrement() throws Exception {
        var target = fixture.targets.getFirst();
        jdbc.update("DELETE FROM likes WHERE user_id = ? AND list_id = ?", fixture.keeper, target.id());
        jdbc.update("UPDATE user_lists SET likes_count = 1 WHERE id = ?", target.id());
        raceAtCleanup(() -> likes.likeList(fixture.keeper, target.id()), fixture.keeper, false);
        fixture.assertCounts(1);
    }

    @Test void targetDeletedAfterCaptureDoesNotProducePhantomDecrement() throws Exception {
        var target = fixture.targets.get(1);
        CountDownLatch captured = new CountDownLatch(1), continueDeletion = new CountDownLatch(1);
        doAnswer(call -> { Object result = call.callRealMethod(); captured.countDown(); await(continueDeletion); return result; })
                .when(likeRepository).findAccountLikeTargets(fixture.actor);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> deleting = pool.submit(this::deleteAccount);
            try {
                assertThat(captured.await(10, SECONDS)).isTrue();
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        jdbc.update("DELETE FROM comments WHERE id = ?", target.id()));
            } finally { continueDeletion.countDown(); }
            deleting.get(15, SECONDS);
        }
        assertThat(count("SELECT COUNT(*) FROM likes WHERE comment_id = ?", target.id())).isZero();
        for (var survivor : fixture.targets) if (!survivor.equals(target)) {
            assertThat(jdbc.queryForObject("SELECT likes_count FROM " + survivor.table() + " WHERE id = ?", Integer.class, survivor.id())).isEqualTo(1);
        }
    }

    void raceAtCleanup(Runnable mutation, UUID concurrentActor, boolean actorDeleted) throws Exception {
        CountDownLatch captured = new CountDownLatch(1), release = new CountDownLatch(1), entered = new CountDownLatch(1);
        doAnswer(call -> { captured.countDown(); await(release); return call.callRealMethod(); })
                .when(likeRepository).deleteAccountLikesAndReconcile(fixture.actor);
        doAnswer(call -> { entered.countDown(); return call.callRealMethod(); })
                .when(userRepository).findByIdForLifecycle(concurrentActor);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> deleting = pool.submit(this::deleteAccount);
            Future<?> mutating;
            try {
                assertThat(captured.await(10, SECONDS)).isTrue();
                mutating = pool.submit(mutation);
                assertThat(entered.await(10, SECONDS)).isTrue();
            } finally { release.countDown(); }
            deleting.get(15, SECONDS);
            if (actorDeleted) assertThatThrownBy(() -> mutating.get(15, SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(NotFoundException.class);
            else mutating.get(15, SECONDS);
        }
    }

    static void await(CountDownLatch latch) throws InterruptedException { assertThat(latch.await(10, SECONDS)).isTrue(); }
    long count(String sql, Object argument) { return jdbc.queryForObject(sql, Long.class, argument); }
}
