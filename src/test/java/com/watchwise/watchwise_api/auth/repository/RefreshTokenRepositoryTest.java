package com.watchwise.watchwise_api.auth.repository;

import com.watchwise.watchwise_api.auth.entity.RefreshToken;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RefreshTokenRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User user;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(buildUser("lucas", "lucas@email.com"));
    }

    @Test
    @DisplayName("[save] Should Persist And Retrieve Refresh Token By Id - When Id Matches Generated Jti")
    void shouldPersistAndRetrieveRefreshTokenByIdWhenIdMatchesGeneratedJti() {
        UUID jti = UUID.randomUUID();
        RefreshToken refreshToken = buildRefreshToken(jti, user);

        refreshTokenRepository.save(refreshToken);

        Optional<RefreshToken> found = refreshTokenRepository.findById(jti);

        assertThat(found).isPresent();
        assertThat(found.get().getUser().getId()).isEqualTo(user.getId());
        assertThat(found.get().getRevoked()).isFalse();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Refresh Tokens - When Owning User Is Deleted")
    void shouldCascadeDeleteRefreshTokensWhenOwningUserIsDeleted() {
        UUID jti = UUID.randomUUID();
        refreshTokenRepository.saveAndFlush(buildRefreshToken(jti, user));
        entityManager.clear();

        User managedUser = userRepository.findById(user.getId()).orElseThrow();
        userRepository.delete(managedUser);
        userRepository.flush();

        assertThat(refreshTokenRepository.findById(jti)).isEmpty();
    }

    private RefreshToken buildRefreshToken(UUID id, User owner) {
        return RefreshToken.builder()
                .id(id)
                .user(owner)
                .expiresAt(LocalDateTime.now().plusDays(7))
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