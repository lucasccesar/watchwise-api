package com.watchwise.watchwise_api.follower.repository;

import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class FollowerRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private FollowerRepository followerRepository;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User lucas;
    private User marina;
    private User joao;

    @BeforeEach
    void setUp() {
        followerRepository.deleteAll();
        userRepository.deleteAll();

        lucas = userRepository.save(buildUser("lucas", "lucas@email.com"));
        marina = userRepository.save(buildUser("marina", "marina@email.com"));
        joao = userRepository.save(buildUser("joao", "joao@email.com"));
    }

    @Test
    @DisplayName("[findByFollowerIdAndFollowedId] Should Return The Row - When It Exists Regardless Of Status")
    void shouldReturnTheRowWhenItExistsRegardlessOfStatus() {
        Follower saved = followerRepository.saveAndFlush(buildFollower(lucas, marina, FollowStatus.PENDING));
        entityManager.clear();

        Optional<Follower> result = followerRepository.findByFollowerIdAndFollowedId(lucas.getId(), marina.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("[findByFollowerIdAndFollowedId] Should Return Empty - When It Does Not Exist")
    void shouldReturnEmptyWhenItDoesNotExist() {
        Optional<Follower> result = followerRepository.findByFollowerIdAndFollowedId(lucas.getId(), marina.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findByFollowerIdAndFollowedIdAndStatus] Should Return The Row - When Status Matches")
    void shouldReturnTheRowWhenStatusMatches() {
        followerRepository.saveAndFlush(buildFollower(lucas, marina, FollowStatus.PENDING));
        entityManager.clear();

        Optional<Follower> result = followerRepository
                .findByFollowerIdAndFollowedIdAndStatus(lucas.getId(), marina.getId(), FollowStatus.PENDING);

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("[findByFollowerIdAndFollowedIdAndStatus] Should Return Empty - When Status Does Not Match")
    void shouldReturnEmptyWhenStatusDoesNotMatch() {
        followerRepository.saveAndFlush(buildFollower(lucas, marina, FollowStatus.PENDING));
        entityManager.clear();

        Optional<Follower> result = followerRepository
                .findByFollowerIdAndFollowedIdAndStatus(lucas.getId(), marina.getId(), FollowStatus.ACCEPTED);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[existsByFollowerIdAndFollowedIdAndStatus] Should Return True - When An Accepted Relationship Exists")
    void shouldReturnTrueWhenAnAcceptedRelationshipExists() {
        followerRepository.saveAndFlush(buildFollower(lucas, marina, FollowStatus.ACCEPTED));
        entityManager.clear();

        boolean result = followerRepository
                .existsByFollowerIdAndFollowedIdAndStatus(lucas.getId(), marina.getId(), FollowStatus.ACCEPTED);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("[existsByFollowerIdAndFollowedIdAndStatus] Should Return False - When Only A Pending Request Exists")
    void shouldReturnFalseWhenOnlyAPendingRequestExists() {
        followerRepository.saveAndFlush(buildFollower(lucas, marina, FollowStatus.PENDING));
        entityManager.clear();

        boolean result = followerRepository
                .existsByFollowerIdAndFollowedIdAndStatus(lucas.getId(), marina.getId(), FollowStatus.ACCEPTED);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("[existsByFollowerIdAndFollowedIdAndStatus] Should Return False - When No Relationship Exists")
    void shouldReturnFalseWhenNoRelationshipExists() {
        boolean result = followerRepository
                .existsByFollowerIdAndFollowedIdAndStatus(lucas.getId(), marina.getId(), FollowStatus.ACCEPTED);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("[findByFollowedIdAndStatus] Should Return Only Accepted Followers Of The Given User - When Called")
    void shouldReturnOnlyAcceptedFollowersOfTheGivenUserWhenCalled() {
        followerRepository.save(buildFollower(lucas, joao, FollowStatus.ACCEPTED));
        followerRepository.save(buildFollower(marina, joao, FollowStatus.PENDING));
        followerRepository.saveAndFlush(buildFollower(lucas, marina, FollowStatus.ACCEPTED));
        entityManager.clear();

        Page<Follower> result = followerRepository.findByFollowedIdAndStatus(joao.getId(), FollowStatus.ACCEPTED, PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(follower -> follower.getFollower().getId())
                .containsExactly(lucas.getId());
    }

    @Test
    @DisplayName("[findByFollowedIdAndStatus] Should Return Only Pending Requests Of The Given User - When Called")
    void shouldReturnOnlyPendingRequestsOfTheGivenUserWhenCalled() {
        followerRepository.save(buildFollower(lucas, joao, FollowStatus.ACCEPTED));
        followerRepository.saveAndFlush(buildFollower(marina, joao, FollowStatus.PENDING));
        entityManager.clear();

        Page<Follower> result = followerRepository.findByFollowedIdAndStatus(joao.getId(), FollowStatus.PENDING, PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(follower -> follower.getFollower().getId())
                .containsExactly(marina.getId());
    }

    @Test
    @DisplayName("[findByFollowerIdAndStatus] Should Return Only Accepted Users The Given User Follows - When Called")
    void shouldReturnOnlyAcceptedUsersTheGivenUserFollowsWhenCalled() {
        followerRepository.save(buildFollower(lucas, joao, FollowStatus.ACCEPTED));
        followerRepository.save(buildFollower(lucas, marina, FollowStatus.PENDING));
        followerRepository.saveAndFlush(buildFollower(marina, joao, FollowStatus.ACCEPTED));
        entityManager.clear();

        Page<Follower> result = followerRepository.findByFollowerIdAndStatus(lucas.getId(), FollowStatus.ACCEPTED, PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(follower -> follower.getFollowed().getId())
                .containsExactly(joao.getId());
    }

    @Test
    @DisplayName("[acceptAllPendingFollowRequestsFor] Should Accept Only Pending Requests Targeting The Given User - When Called")
    void shouldAcceptOnlyPendingRequestsTargetingTheGivenUserWhenCalled() {
        Follower pendingForJoao = followerRepository.save(buildFollower(lucas, joao, FollowStatus.PENDING));
        Follower alreadyAcceptedForJoao = followerRepository.save(buildFollower(marina, joao, FollowStatus.ACCEPTED));
        Follower pendingForSomeoneElse = followerRepository.saveAndFlush(buildFollower(joao, marina, FollowStatus.PENDING));
        entityManager.clear();

        followerRepository.acceptAllPendingFollowRequestsFor(joao.getId());
        entityManager.clear();

        assertThat(followerRepository.findById(pendingForJoao.getId()).orElseThrow().getStatus())
                .isEqualTo(FollowStatus.ACCEPTED);
        assertThat(followerRepository.findById(alreadyAcceptedForJoao.getId()).orElseThrow().getStatus())
                .isEqualTo(FollowStatus.ACCEPTED);
        assertThat(followerRepository.findById(pendingForSomeoneElse.getId()).orElseThrow().getStatus())
                .isEqualTo(FollowStatus.PENDING);
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Follower Rows - When The Follower User Is Deleted")
    void shouldCascadeDeleteFollowerRowsWhenTheFollowerUserIsDeleted() {
        Follower saved = followerRepository.saveAndFlush(buildFollower(lucas, marina, FollowStatus.ACCEPTED));
        entityManager.clear();

        userRepository.delete(userRepository.findById(lucas.getId()).orElseThrow());
        userRepository.flush();

        assertThat(followerRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Follower Rows - When The Followed User Is Deleted")
    void shouldCascadeDeleteFollowerRowsWhenTheFollowedUserIsDeleted() {
        Follower saved = followerRepository.saveAndFlush(buildFollower(lucas, marina, FollowStatus.ACCEPTED));
        entityManager.clear();

        userRepository.delete(userRepository.findById(marina.getId()).orElseThrow());
        userRepository.flush();

        assertThat(followerRepository.findById(saved.getId())).isEmpty();
    }

    private Follower buildFollower(User follower, User followed, FollowStatus status) {
        return Follower.builder()
                .follower(follower)
                .followed(followed)
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private User buildUser(String username, String email) {
        return User.builder()
                .username(username)
                .email(email)
                .password("hashed_password")
                .profilePicture("https://example.com/photo.png")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}