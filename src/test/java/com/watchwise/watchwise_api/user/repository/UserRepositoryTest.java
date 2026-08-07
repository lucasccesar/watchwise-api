package com.watchwise.watchwise_api.user.repository;

import com.watchwise.watchwise_api.user.entity.User;
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

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        userRepository.save(buildUser("lucas", "lucas@email.com", true));
        userRepository.save(buildUser("lucasc", "lucasc@email.com", true));
        userRepository.save(buildUser("lucasprivado", "lucasprivado@email.com", false));
        userRepository.save(buildUser("marina", "marina@email.com", true));
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Return Public And Private Users - When OnlyPublic Is False")
    void shouldReturnPublicAndPrivateUsersWhenOnlyPublicIsFalse() {
        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "lucas", false, PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(User::getUsername)
                .containsExactlyInAnyOrder("lucas", "lucasc", "lucasprivado");
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Return Only Public Users - When OnlyPublic Is True")
    void shouldReturnOnlyPublicUsersWhenOnlyPublicIsTrue() {
        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "lucas", true, PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(User::getUsername)
                .containsExactlyInAnyOrder("lucas", "lucasc");
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Return Exact Match First - When Username Matches Exactly")
    void shouldReturnExactMatchFirst() {
        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "lucas", false, PageRequest.of(0, 10));

        assertThat(result.getContent().get(0).getUsername()).isEqualTo("lucas");
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Match Regardless Of Case - When Username Prefix Has A Different Case")
    void shouldBeCaseInsensitive() {
        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "LUCAS", false, PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(User::getUsername)
                .containsExactlyInAnyOrder("lucas", "lucasc", "lucasprivado");
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Return Empty Page - When No Username Matches Prefix")
    void shouldReturnEmptyPageWhenNoUsernameMatchesPrefix() {
        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "zzz", false, PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Not Return Username - When Prefix Appears In The Middle Instead Of The Start")
    void shouldNotReturnUsernameContainingPrefixInTheMiddle() {
        userRepository.save(buildUser("xlucas", "xlucas@email.com", true));

        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "lucas", false, PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(User::getUsername)
                .doesNotContain("xlucas");
    }

    @Test
    @DisplayName("[findByUsernameStartingWithIgnoreCase] Should Respect Page Size - When Page Size Is Provided")
    void shouldRespectPageSize() {
        Page<User> result = userRepository.findByUsernameStartingWithIgnoreCase(
                "lucas", false, PageRequest.of(0, 2));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
    }

    private User buildUser(String username, String email, boolean isProfilePublic) {
        return User.builder()
                .username(username)
                .email(email)
                .password("hashed_password")
                .profilePicture("https://example.com/photo.png")
                .isProfilePublic(isProfilePublic)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}