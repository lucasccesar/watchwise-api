package com.watchwise.watchwise_api.userblock.repository;

import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.userblock.entity.UserBlock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserBlockRepositoryTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired UserBlockRepository repository;
    @Autowired UserRepository userRepository;
    @PersistenceContext EntityManager entityManager;
    private User userA;
    private User userB;
    private User userC;

    @BeforeEach void setUp() {
        repository.deleteAll();
        userRepository.deleteAll();
        userA = userRepository.saveAndFlush(user("blocka"));
        userB = userRepository.saveAndFlush(user("blockb"));
        userC = userRepository.saveAndFlush(user("blockc"));
    }

    @Test void shouldFindBlockInEitherDirectionAndReturnBlockedUsersInStableNewestFirstOrder() {
        UserBlock saved = repository.saveAndFlush(block(userA, userB));
        entityManager.clear();
        assertThat(repository.existsEitherDirection(userA.getId(), userB.getId())).isTrue();
        assertThat(repository.existsEitherDirection(userA.getId(), userC.getId())).isFalse();
        assertThat(repository.findByBlockerIdOrderByCreatedAtDescIdDesc(userA.getId(), PageRequest.of(0, 20))
                .getContent()).extracting(block -> block.getBlocked().getId()).containsExactly(userB.getId());
        assertThat(saved.getId()).isNotNull();
    }

    @Test void shouldRejectDuplicateBlockPair() {
        repository.saveAndFlush(block(userA, userB));
        assertThatThrownBy(() -> repository.saveAndFlush(block(userA, userB)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void shouldRejectSelfBlock() {
        assertThatThrownBy(() -> repository.saveAndFlush(block(userA, userA)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void shouldReturnBlockedUsersNewestFirst() {
        LocalDateTime now = LocalDateTime.now();
        repository.save(block(userA, userB, now.minusMinutes(1)));
        repository.saveAndFlush(block(userA, userC, now));
        entityManager.clear();

        assertThat(repository.findByBlockerIdOrderByCreatedAtDescIdDesc(userA.getId(), PageRequest.of(0, 20))
                .getContent()).extracting(block -> block.getBlocked().getId())
                .containsExactly(userC.getId(), userB.getId());
    }

    private UserBlock block(User blocker, User blocked) {
        return block(blocker, blocked, LocalDateTime.now());
    }
    private UserBlock block(User blocker, User blocked, LocalDateTime createdAt) {
        return UserBlock.builder().blocker(blocker).blocked(blocked).createdAt(createdAt).build();
    }
    private User user(String username) {
        return User.builder().username(username).name(username).email(username + "@email.com")
                .password("hashed_password").profilePicture("https://example.com/photo.png")
                .isProfilePublic(true).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
}
