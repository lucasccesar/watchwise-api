package com.watchwise.watchwise_api.search.repository;

import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SearchRepositorySliceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
                SqlCaptureInspector.class::getName);
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserListRepository userListRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        userListRepository.deleteAll();
        userRepository.deleteAll();

        User viewer = userRepository.save(buildUser("viewer", "viewer@email.com"));
        userListRepository.saveAll(IntStream.range(0, 21)
                .mapToObj(index -> buildList(viewer, "needle list " + index))
                .toList());
        userRepository.saveAll(IntStream.range(0, 21)
                .mapToObj(index -> buildUser("needle-user-" + index, "needle-user-" + index + "@email.com"))
                .toList());
        userListRepository.flush();
        userRepository.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("[list search] Should Not Execute A Count Query - When Returning A Slice")
    void shouldNotExecuteCountQueryWhenSearchingLists() {
        SqlCaptureInspector.clear();

        Slice<UserList> result = userListRepository.findVisibleByNameContainingIgnoreCase(
                userRepository.findByUsernameIgnoreCase("viewer").orElseThrow().getId(),
                "needle",
                PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(20);
        assertThat(result.hasNext()).isTrue();
        assertThat(SqlCaptureInspector.statements())
                .noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("count("));
    }

    @Test
    @DisplayName("[user search] Should Not Execute A Count Query - When Returning A Slice")
    void shouldNotExecuteCountQueryWhenSearchingUsers() {
        SqlCaptureInspector.clear();

        Slice<User> result = userRepository.findByUsernameStartingWithIgnoreCaseForSearch(
                "needle", "needle", PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(20);
        assertThat(result.hasNext()).isTrue();
        assertThat(SqlCaptureInspector.statements())
                .noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("count("));
    }

    private User buildUser(String username, String email) {
        LocalDateTime now = LocalDateTime.now();
        return User.builder()
                .username(username)
                .email(email)
                .password("hashed_password")
                .profilePicture("https://example.com/photo.png")
                .isProfilePublic(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private UserList buildList(User user, String name) {
        LocalDateTime now = LocalDateTime.now();
        return UserList.builder()
                .user(user)
                .name(name)
                .visibility(UserListVisibility.PUBLIC)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public static class SqlCaptureInspector implements StatementInspector {

        private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql);
            return sql;
        }

        static void clear() {
            STATEMENTS.clear();
        }

        static List<String> statements() {
            return List.copyOf(STATEMENTS);
        }
    }
}
