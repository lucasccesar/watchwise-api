package com.watchwise.watchwise_api.user.repository;

import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        entityManager.flush();

        userRepository.save(buildUser("lucas", "lucas@email.com", true));
        userRepository.save(buildUser("lucasc", "lucasc@email.com", true));
        userRepository.save(buildUser("lucasprivado", "lucasprivado@email.com", false));
        userRepository.save(buildUser("marina", "marina@email.com", true));
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Return Public And Private Users - When Called")
    void shouldReturnPublicAndPrivateUsersWhenCalled() {
        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "lucas", "lucas", PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(User::getUsername)
                .containsExactlyInAnyOrder("lucas", "lucasc", "lucasprivado");
    }

    @Test
    @DisplayName("[findByIdForFollowStatus] Should Block A Visibility Update - When Follow Status Read Holds A Shared Lock")
    void shouldBlockAVisibilityUpdateWhenFollowStatusReadHoldsASharedLock() throws Exception {
        UUID ownerId = userRepository.findByUsernameIgnoreCase("lucasprivado").orElseThrow().getId();
        TestTransaction.flagForCommit();
        TestTransaction.end();

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> reader = executor.submit(() -> {
                transactionTemplate.executeWithoutResult(status -> {
                    userRepository.findByIdForFollowStatus(ownerId);
                    firstLocked.countDown();
                    await(releaseFirst);
                });
                return null;
            });
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Void> writer = executor.submit(() -> {
                transactionTemplate.executeWithoutResult(status -> {
                    User owner = userRepository.findById(ownerId).orElseThrow();
                    owner.setIsProfilePublic(true);
                    userRepository.saveAndFlush(owner);
                });
                return null;
            });

            assertThat(writer.isDone()).isFalse();
            releaseFirst.countDown();
            reader.get(5, TimeUnit.SECONDS);
            writer.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            TestTransaction.start();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the first transaction");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the first transaction", e);
        }
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Return Exact Match First - When Username Matches Exactly")
    void shouldReturnExactMatchFirst() {
        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "lucas", "lucas", PageRequest.of(0, 10));

        assertThat(result.getContent().get(0).getUsername()).isEqualTo("lucas");
    }

    @Test
    @DisplayName("[searchByNameOrUsernameForView] Should Match Name And Username In Relevance Order - When Searching Users")
    void shouldMatchNameAndUsernameInRelevanceOrderWhenSearchingUsers() {
        userRepository.save(buildUser("night", "night@email.com", true));
        userRepository.save(buildUser("nightly", "nightly@email.com", true));
        User namedMatch = buildUser("cinema", "cinema@email.com", true);
        namedMatch.setName("Night Cinema");
        userRepository.save(namedMatch);

        Page<User> result = userRepository.searchByNameOrUsernameForView(
                "night", "night", PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(User::getUsername)
                .containsExactly("night", "nightly", "cinema");
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Match Regardless Of Case - When Username Prefix Has A Different Case")
    void shouldBeCaseInsensitive() {
        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "LUCAS", "LUCAS", PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(User::getUsername)
                .containsExactlyInAnyOrder("lucas", "lucasc", "lucasprivado");
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Return Empty Page - When No Username Matches Prefix")
    void shouldReturnEmptyPageWhenNoUsernameMatchesPrefix() {
        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "zzz", "zzz", PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Not Return Username - When Prefix Appears In The Middle Instead Of The Start")
    void shouldNotReturnUsernameContainingPrefixInTheMiddle() {
        userRepository.save(buildUser("xlucas", "xlucas@email.com", true));

        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "lucas", "lucas", PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(User::getUsername)
                .doesNotContain("xlucas");
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Respect Page Size - When Page Size Is Provided")
    void shouldRespectPageSize() {
        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "lucas", "lucas", PageRequest.of(0, 2));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Match Nothing - When Escaped Username Is A Literal Percent Wildcard")
    void shouldMatchNothingWhenEscapedUsernameIsALiteralPercentWildcard() {
        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "%", "\\%", PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Match Nothing - When Escaped Username Is A Literal Underscore Wildcard")
    void shouldMatchNothingWhenEscapedUsernameIsALiteralUnderscoreWildcard() {
        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "_", "\\_", PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[findByEmailIgnoreCase] Should Return User - When Email Matches Regardless Of Case")
    void shouldReturnUserWhenEmailMatchesRegardlessOfCase() {
        Optional<User> result = userRepository.findByEmailIgnoreCase("LUCAS@EMAIL.COM");

        assertThat(result).isPresent();
        assertThat(result.get().getUsername()).isEqualTo("lucas");
    }

    @Test
    @DisplayName("[findByEmailIgnoreCase] Should Return Empty - When No User Has That Email")
    void shouldReturnEmptyWhenNoUserHasThatEmail() {
        Optional<User> result = userRepository.findByEmailIgnoreCase("nobody@email.com");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findById] Should Read USER Role - When SQL Insert Omits Role")
    void shouldReadUserRoleWhenSqlInsertOmitsRole() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, username, name, email, password, profile_picture)
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId, "migrated-user", "migrated-user", "migrated-user@email.com", "hashed_password",
                "https://example.com/photo.png");

        User result = userRepository.findById(userId).orElseThrow();

        assertThat(result.getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    @DisplayName("[saveAndFlush] Should Persist USER Role - When New User Has No Explicit Role")
    void shouldPersistUserRoleWhenNewUserHasNoExplicitRole() {
        User saved = userRepository.saveAndFlush(buildUser("role-default", "role-default@email.com", true));
        entityManager.clear();

        User result = userRepository.findById(saved.getId()).orElseThrow();

        assertThat(result).isNotSameAs(saved);
        assertThat(result.getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    @DisplayName("[findSessionsInvalidatedAtById] Should Return A View With A Null Value - When The User Exists But Never Had Sessions Invalidated")
    void shouldReturnAViewWithANullValueWhenTheUserExistsButNeverHadSessionsInvalidated() {
        User user = userRepository.findByUsernameIgnoreCase("lucas").orElseThrow();

        Optional<UserRepository.SessionsInvalidatedAtView> result = userRepository.findSessionsInvalidatedAtById(user.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getSessionsInvalidatedAt()).isNull();
    }

    @Test
    @DisplayName("[findSessionsInvalidatedAtById] Should Return The Stored Value - When The User Has Sessions Invalidated")
    void shouldReturnTheStoredValueWhenTheUserHasSessionsInvalidated() {
        User user = userRepository.findByUsernameIgnoreCase("lucas").orElseThrow();
        LocalDateTime invalidatedAt = LocalDateTime.now().minusMinutes(5).truncatedTo(ChronoUnit.MICROS);
        userRepository.markSessionsInvalidatedAt(user.getId(), invalidatedAt);

        Optional<UserRepository.SessionsInvalidatedAtView> result = userRepository.findSessionsInvalidatedAtById(user.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getSessionsInvalidatedAt()).isEqualTo(invalidatedAt);
    }

    @Test
    @DisplayName("[findSessionsInvalidatedAtById] Should Return Empty - When No User Has That Id, Not Confused With A Null Stored Value")
    void shouldReturnEmptyWhenNoUserHasThatIdNotConfusedWithANullStoredValue() {
        Optional<UserRepository.SessionsInvalidatedAtView> result =
                userRepository.findSessionsInvalidatedAtById(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    private User buildUser(String username, String email, boolean isProfilePublic) {
        return User.builder()
                .username(username)
                .name(username)
                .email(email)
                .password("hashed_password")
                .profilePicture("https://example.com/photo.png")
                .isProfilePublic(isProfilePublic)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
