package com.watchwise.watchwise_api.followedperson.repository;

import com.watchwise.watchwise_api.followedperson.entity.FollowedPerson;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class FollowedPersonRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private FollowedPersonRepository followedPersonRepository;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User lucas;
    private User marina;

    @BeforeEach
    void setUp() {
        followedPersonRepository.deleteAll();
        userRepository.deleteAll();

        lucas = userRepository.save(buildUser("lucas", "lucas@email.com"));
        marina = userRepository.save(buildUser("marina", "marina@email.com"));
    }

    @Test
    @DisplayName("[findByUserIdAndPersonTmdbId] Should Return The Row - When It Exists")
    void shouldReturnTheRowWhenItExists() {
        FollowedPerson saved = followedPersonRepository.saveAndFlush(buildFollowedPerson(lucas, "123"));
        entityManager.clear();

        Optional<FollowedPerson> result = followedPersonRepository.findByUserIdAndPersonTmdbId(lucas.getId(), "123");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndPersonTmdbId] Should Return Empty - When It Does Not Exist")
    void shouldReturnEmptyWhenItDoesNotExist() {
        Optional<FollowedPerson> result = followedPersonRepository.findByUserIdAndPersonTmdbId(lucas.getId(), "123");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findByUserIdAndPersonTmdbId] Should Return Empty - When Person Is Followed By A Different User")
    void shouldReturnEmptyWhenPersonIsFollowedByADifferentUser() {
        followedPersonRepository.saveAndFlush(buildFollowedPerson(marina, "123"));
        entityManager.clear();

        Optional<FollowedPerson> result = followedPersonRepository.findByUserIdAndPersonTmdbId(lucas.getId(), "123");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[existsByUserIdAndPersonTmdbId] Should Return True - When The Row Exists")
    void shouldReturnTrueWhenTheRowExists() {
        followedPersonRepository.saveAndFlush(buildFollowedPerson(lucas, "456"));
        entityManager.clear();

        boolean result = followedPersonRepository.existsByUserIdAndPersonTmdbId(lucas.getId(), "456");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("[existsByUserIdAndPersonTmdbId] Should Return False - When The Row Does Not Exist")
    void shouldReturnFalseWhenTheRowDoesNotExist() {
        boolean result = followedPersonRepository.existsByUserIdAndPersonTmdbId(lucas.getId(), "456");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When User Already Follows That Person")
    void shouldThrowDataIntegrityViolationExceptionWhenUserAlreadyFollowsThatPerson() {
        followedPersonRepository.saveAndFlush(buildFollowedPerson(lucas, "789"));
        entityManager.clear();

        assertThatThrownBy(() -> followedPersonRepository.saveAndFlush(buildFollowedPerson(lucas, "789")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Allow The Same Person To Be Followed By Different Users")
    void shouldAllowTheSamePersonToBeFollowedByDifferentUsers() {
        followedPersonRepository.saveAndFlush(buildFollowedPerson(lucas, "999"));
        entityManager.clear();

        FollowedPerson saved = followedPersonRepository.saveAndFlush(buildFollowedPerson(marina, "999"));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Followed People Rows - When The User Is Deleted")
    void shouldCascadeDeleteFollowedPeopleRowsWhenTheUserIsDeleted() {
        FollowedPerson saved = followedPersonRepository.saveAndFlush(buildFollowedPerson(lucas, "321"));
        entityManager.clear();

        userRepository.delete(userRepository.findById(lucas.getId()).orElseThrow());
        userRepository.flush();

        assertThat(followedPersonRepository.findById(saved.getId())).isEmpty();
    }

    private FollowedPerson buildFollowedPerson(User user, String personTmdbId) {
        return FollowedPerson.builder()
                .user(user)
                .personTmdbId(personTmdbId)
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