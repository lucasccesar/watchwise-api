package com.watchwise.watchwise_api.userblock.service.impl;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.transaction.PostgresAdvisoryLock;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.follower.service.FollowerService;
import com.watchwise.watchwise_api.follower.service.impl.FollowerServiceImpl;
import com.watchwise.watchwise_api.notification.service.FollowNotificationService;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.entity.UserBlock;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import com.watchwise.watchwise_api.userblock.service.UserBlockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({UserBlockServiceImpl.class, UserBlockAccessServiceImpl.class, FollowerServiceImpl.class,
        PostgresAdvisoryLock.class, PageRequestFactory.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserBlockConcurrencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired UserBlockService blockService;
    @Autowired FollowerService followerService;
    @Autowired UserRepository userRepository;
    @MockitoSpyBean UserBlockRepository blockRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean FollowerRepository followerRepository;
    @MockitoSpyBean NotificationRepository notificationRepository;
    @MockitoBean UserMapper userMapper;
    @MockitoBean FollowNotificationService followNotificationService;

    private User first;
    private User second;

    @BeforeEach
    void setUp() {
        followerRepository.deleteAll();
        blockRepository.deleteAll();
        userRepository.deleteAll();
        first = saveUser("pair-first", true);
        second = saveUser("pair-second", true);
    }

    @ParameterizedTest
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    @DisplayName("[block] Should Remove The Concurrent Follow - When Follow Passes Its Check Before Either Block Direction")
    void shouldRemoveConcurrentFollowWhenFollowPassesCheckBeforeBlock(boolean reverseBlock, boolean publicTarget)
            throws Exception {
        second.setIsProfilePublic(publicTarget);
        second = userRepository.saveAndFlush(second);
        CountDownLatch followChecked = new CountDownLatch(1);
        CountDownLatch releaseFollow = new CountDownLatch(1);
        doAnswer(invocation -> {
            followChecked.countDown();
            await(releaseFollow);
            return callRepository(invocation);
        }).when(followerRepository).saveAndFlush(any(Follower.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicLong blockPid = new AtomicLong();
        CountDownLatch blockStarted = new CountDownLatch(1);
        try {
            Future<FollowStatus> follow = executor.submit(() -> followerService.followUser(first.getId(), second.getId()));
            assertThat(followChecked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> block = executor.submit(() -> inTransaction(() -> {
                blockPid.set(currentBackendPid());
                blockStarted.countDown();
                block(reverseBlock);
            }));
            assertThat(blockStarted.await(10, TimeUnit.SECONDS)).isTrue();
            boolean serialized = awaitAdvisoryWaitOrCompletion(blockPid.get(), block);
            releaseFollow.countDown();
            assertThat(follow.get(10, TimeUnit.SECONDS))
                    .isEqualTo(publicTarget ? FollowStatus.ACCEPTED : FollowStatus.PENDING);
            block.get(10, TimeUnit.SECONDS);

            assertThat(followerRepository.count()).isZero();
            assertThat(blockRepository.count()).isEqualTo(1);
            assertThat(serialized).as("blocking waits for the follow transaction").isTrue();
        } finally {
            releaseFollow.countDown();
            shutdown(executor);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    @DisplayName("[followUser] Should Reject The Concurrent Follow - When Either Block Direction Commits First")
    void shouldRejectConcurrentFollowWhenBlockCommitsFirst(boolean reverseBlock, boolean publicTarget) throws Exception {
        second.setIsProfilePublic(publicTarget);
        second = userRepository.saveAndFlush(second);
        assertMutationWaitsForBlock(reverseBlock, ForbiddenException.class,
                () -> followerService.followUser(first.getId(), second.getId()));
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    @DisplayName("[acceptFollowRequest] Should Reject Acceptance - When Either Block Direction Commits First")
    void shouldRejectAcceptanceWhenBlockCommitsFirst(boolean reverseBlock) throws Exception {
        saveFollow(first, second, FollowStatus.PENDING);
        assertMutationWaitsForBlock(reverseBlock, NotFoundException.class,
                () -> followerService.acceptFollowRequest(second.getId(), first.getId()));
    }

    @Test
    @DisplayName("[acceptAllPendingFollowRequestsFor] Should Not Accept A Pending Request - When Block Wins The Pair Lock")
    void shouldNotAcceptAllPendingRequestWhenBlockWinsThePairLock() throws Exception {
        saveFollow(first, second, FollowStatus.PENDING);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        pauseCleanup(first.getId(), second.getId(), cleanupStarted, releaseCleanup);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicLong acceptancePid = new AtomicLong();
        CountDownLatch acceptanceStarted = new CountDownLatch(1);
        try {
            Future<?> block = executor.submit(() -> block(false));
            assertThat(cleanupStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> acceptance = executor.submit(() -> inTransaction(() -> {
                acceptancePid.set(currentBackendPid());
                acceptanceStarted.countDown();
                followerService.acceptAllPendingFollowRequestsFor(second);
            }));
            assertThat(acceptanceStarted.await(10, TimeUnit.SECONDS)).isTrue();
            boolean serialized = awaitAdvisoryWaitOrCompletion(acceptancePid.get(), acceptance);
            releaseCleanup.countDown();
            block.get(10, TimeUnit.SECONDS);
            acceptance.get(10, TimeUnit.SECONDS);

            assertThat(followerRepository.findByFollowerIdAndFollowedId(first.getId(), second.getId())).isEmpty();
            assertThat(blockRepository.count()).isEqualTo(1);
            assertThat(serialized).as("bulk acceptance waits for the block transaction").isTrue();
        } finally {
            releaseCleanup.countDown();
            shutdown(executor);
        }
    }

    @Test
    @DisplayName("[block] Should Roll Back The Block And Both Deletions - When The Second Cleanup Fails")
    void shouldRollBackBlockAndBothDeletionsWhenSecondCleanupFails() {
        saveFollow(first, second, FollowStatus.ACCEPTED);
        saveFollow(second, first, FollowStatus.PENDING);
        doAnswer(invocation -> {
            callRepository(invocation);
            followerRepository.flush();
            throw new IllegalStateException("cleanup failed");
        }).when(followerRepository).deleteByFollowerIdAndFollowedId(second.getId(), first.getId());

        assertThatThrownBy(() -> blockService.block(first.getId(), second.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("cleanup failed");

        assertThat(blockRepository.count()).isZero();
        assertThat(followerRepository.findByFollowerIdAndFollowedId(first.getId(), second.getId()))
                .get().extracting(Follower::getStatus).isEqualTo(FollowStatus.ACCEPTED);
        assertThat(followerRepository.findByFollowerIdAndFollowedId(second.getId(), first.getId()))
                .get().extracting(Follower::getStatus).isEqualTo(FollowStatus.PENDING);
    }

    @Test
    @DisplayName("[block] Should Roll Back The Block And Follow Cleanup - When Notification Cleanup Fails")
    void shouldRollBackBlockAndFollowCleanupWhenNotificationCleanupFails() {
        saveFollow(first, second, FollowStatus.ACCEPTED);
        LocalDateTime now = LocalDateTime.now();
        Notification pairNotification = notificationRepository.saveAndFlush(Notification.builder()
                .user(first).latestActor(second).type(NotificationType.NEW_FOLLOWER)
                .message("followed you").isRead(false).createdAt(now).updatedAt(now).build());
        doAnswer(invocation -> {
            jdbcTemplate.update("DELETE FROM notifications WHERE "
                            + "(user_id = ? AND actor_user_id = ?) OR (user_id = ? AND actor_user_id = ?)",
                    first.getId(), second.getId(), second.getId(), first.getId());
            throw new IllegalStateException("notification cleanup failed");
        }).when(notificationRepository).deleteBetweenUsers(first.getId(), second.getId());

        assertThatThrownBy(() -> blockService.block(first.getId(), second.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("notification cleanup failed");

        assertThat(blockRepository.count()).isZero();
        assertThat(followerRepository.findByFollowerIdAndFollowedId(first.getId(), second.getId()))
                .get().extracting(Follower::getStatus).isEqualTo(FollowStatus.ACCEPTED);
        assertThat(notificationRepository.findById(pairNotification.getId())).isPresent();
    }

    @Test
    @DisplayName("[block] Should Keep One Block And Remove Both Follows - When Duplicate Commands Overlap")
    void shouldKeepOneBlockWhenDuplicateCommandsOverlap() throws Exception {
        saveFollow(first, second, FollowStatus.ACCEPTED);
        saveFollow(second, first, FollowStatus.PENDING);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        pauseCleanup(first.getId(), second.getId(), cleanupStarted, releaseCleanup);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicLong duplicatePid = new AtomicLong();
        CountDownLatch duplicateStarted = new CountDownLatch(1);
        try {
            Future<?> initial = executor.submit(() -> block(false));
            assertThat(cleanupStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> duplicate = executor.submit(() -> inTransaction(() -> {
                duplicatePid.set(currentBackendPid());
                duplicateStarted.countDown();
                block(false);
            }));
            assertThat(duplicateStarted.await(10, TimeUnit.SECONDS)).isTrue();
            boolean serialized = awaitAdvisoryWaitOrCompletion(duplicatePid.get(), duplicate);
            releaseCleanup.countDown();
            initial.get(10, TimeUnit.SECONDS);
            duplicate.get(10, TimeUnit.SECONDS);
            assertThat(blockRepository.count()).isEqualTo(1);
            assertThat(followerRepository.count()).isZero();
            assertThat(serialized).as("duplicate checks run after the winning transaction commits").isTrue();
        } finally {
            releaseCleanup.countDown();
            shutdown(executor);
        }
    }

    @Test
    @DisplayName("[unblock] Should Serialize A Concurrent Reblock - When Unblock Read The Existing Row First")
    void shouldSerializeConcurrentReblockWhenUnblockReadTheExistingRowFirst() throws Exception {
        blockRepository.saveAndFlush(UserBlock.builder().blocker(first).blocked(second)
                .createdAt(LocalDateTime.now()).build());
        CountDownLatch unblockRead = new CountDownLatch(1);
        CountDownLatch releaseUnblock = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = callBlockRepository(invocation);
            unblockRead.countDown();
            await(releaseUnblock);
            return result;
        }).when(blockRepository).findByBlockerIdAndBlockedId(first.getId(), second.getId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicLong reblockPid = new AtomicLong();
        CountDownLatch reblockStarted = new CountDownLatch(1);
        try {
            Future<?> unblock = executor.submit(() -> blockService.unblock(first.getId(), second.getId()));
            assertThat(unblockRead.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> reblock = executor.submit(() -> inTransaction(() -> {
                reblockPid.set(currentBackendPid());
                reblockStarted.countDown();
                blockService.block(first.getId(), second.getId());
            }));
            assertThat(reblockStarted.await(10, TimeUnit.SECONDS)).isTrue();
            boolean serialized = awaitAdvisoryWaitOrCompletion(reblockPid.get(), reblock);
            releaseUnblock.countDown();
            unblock.get(10, TimeUnit.SECONDS);
            reblock.get(10, TimeUnit.SECONDS);

            assertThat(blockRepository.existsByBlockerIdAndBlockedId(first.getId(), second.getId())).isTrue();
            assertThat(blockRepository.count()).isEqualTo(1);
            assertThat(serialized).as("the new block waits for the in-flight unblock").isTrue();
        } finally {
            releaseUnblock.countDown();
            shutdown(executor);
        }
    }

    private void assertMutationWaitsForBlock(boolean reverseBlock, Class<? extends Throwable> expectedException,
            Runnable mutation) throws Exception {
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        User blocker = reverseBlock ? second : first;
        User blocked = reverseBlock ? first : second;
        pauseCleanup(blocked.getId(), blocker.getId(), cleanupStarted, releaseCleanup);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicLong mutationPid = new AtomicLong();
        CountDownLatch mutationStarted = new CountDownLatch(1);
        try {
            Future<?> block = executor.submit(() -> block(reverseBlock));
            assertThat(cleanupStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> follow = executor.submit(() -> catchThrowable(() -> inTransaction(() -> {
                mutationPid.set(currentBackendPid());
                mutationStarted.countDown();
                mutation.run();
            })));
            assertThat(mutationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            boolean serialized = awaitAdvisoryWaitOrCompletion(mutationPid.get(), follow);
            releaseCleanup.countDown();
            block.get(10, TimeUnit.SECONDS);
            assertThat(follow.get(10, TimeUnit.SECONDS)).isInstanceOf(expectedException);
            assertThat(followerRepository.count()).isZero();
            assertThat(blockRepository.count()).isEqualTo(1);
            assertThat(serialized).as("the block check waits for the block transaction").isTrue();
        } finally {
            releaseCleanup.countDown();
            shutdown(executor);
        }
    }

    private void pauseCleanup(UUID followerId, UUID followedId, CountDownLatch started, CountDownLatch release) {
        doAnswer(invocation -> {
            started.countDown();
            await(release);
            return callRepository(invocation);
        }).when(followerRepository).deleteByFollowerIdAndFollowedId(followerId, followedId);
    }

    private void block(boolean reverse) {
        blockService.block(reverse ? second.getId() : first.getId(), reverse ? first.getId() : second.getId());
    }

    private Object callRepository(InvocationOnMock invocation) throws Throwable {
        return mockingDetails(followerRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
    }

    private Object callBlockRepository(InvocationOnMock invocation) throws Throwable {
        return mockingDetails(blockRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
    }

    private User saveUser(String username, boolean publicProfile) {
        return userRepository.saveAndFlush(User.builder().username(username).name(username)
                .email(username + "@email.com").password("hash").isProfilePublic(publicProfile)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    private void saveFollow(User follower, User followed, FollowStatus status) {
        followerRepository.saveAndFlush(Follower.builder().follower(follower).followed(followed)
                .status(status).createdAt(LocalDateTime.now()).build());
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private long currentBackendPid() {
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class);
    }

    private boolean awaitAdvisoryWaitOrCompletion(long pid, Future<?> operation) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_locks
                    WHERE pid = ? AND locktype = 'advisory' AND granted = false)
                    """, Boolean.class, pid))) {
                return true;
            }
            if (operation.isDone()) {
                return false;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Transaction neither waited for its pair lock nor completed");
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("transaction release").isTrue();
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
}
