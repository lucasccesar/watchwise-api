package com.watchwise.watchwise_api.search.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsInAnyOrder;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SearchEndToEndIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserListRepository userListRepository;

    @Autowired
    private UserListItemRepository userListItemRepository;

    @Autowired
    private FollowerRepository followerRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @MockitoBean
    private TmdbClient tmdbClient;

    @BeforeEach
    void setUp() {
        userListItemRepository.deleteAll();
        userListRepository.deleteAll();
        followerRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        reset(tmdbClient);
        RequestThrottlerTestSupport.reset(requestThrottler);

        when(tmdbClient.searchMulti("Shared", "en-US", 1))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(1, List.of(), 1, 0)));
    }

    private record RegisteredUser(UUID id, Cookie accessToken) {
    }

    @Test
    @DisplayName("[search] Should Apply List Visibility Through Real Controller And Service")
    void shouldApplyListVisibilityThroughRealControllerAndService() throws Exception {
        RegisteredUser owner = registerUser("visibilityowner");
        RegisteredUser acceptedFollower = registerUser("visibilityfollower");
        RegisteredUser stranger = registerUser("visibilitystranger");

        persistAcceptedFollow(acceptedFollower.id(), owner.id());
        persistList(owner.id(), "Shared public", UserListVisibility.PUBLIC);
        persistList(owner.id(), "Shared followers", UserListVisibility.FOLLOWERS);
        persistList(owner.id(), "Shared private", UserListVisibility.PRIVATE);

        assertVisibleListNames(owner.accessToken(), "Shared public", "Shared followers", "Shared private");
        assertVisibleListNames(acceptedFollower.accessToken(), "Shared public", "Shared followers");
        assertVisibleListNames(stranger.accessToken(), "Shared public");
    }

    private void assertVisibleListNames(Cookie accessToken, String... expectedNames) throws Exception {
        MockHttpServletRequestBuilder request = get("/search")
                .param("q", "Shared")
                .cookie(accessToken);

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contents").isArray())
                .andExpect(jsonPath("$.contents.length()").value(0))
                .andExpect(jsonPath("$.lists").isArray())
                .andExpect(jsonPath("$.lists[*].name").value(containsInAnyOrder(expectedNames)))
                .andExpect(jsonPath("$.users").isArray())
                .andExpect(jsonPath("$.users.length()").value(0));
    }

    private RegisteredUser registerUser(String username) throws Exception {
        MvcResult result = mockMvc.perform(registerRequest(username))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessToken = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessToken).isNotNull();

        User user = userRepository.findByUsernameIgnoreCase(username).orElseThrow();
        return new RegisteredUser(user.getId(), accessToken);
    }

    private MockHttpServletRequestBuilder registerRequest(String username) {
        String body = """
                {
                    "username": "%s",
                    "email": "%s@email.com",
                    "password": "Password123"
                }
                """.formatted(username, username);

        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private void persistAcceptedFollow(UUID followerId, UUID followedId) {
        User follower = userRepository.findById(followerId).orElseThrow();
        User followed = userRepository.findById(followedId).orElseThrow();
        followerRepository.save(Follower.builder()
                .follower(follower)
                .followed(followed)
                .status(FollowStatus.ACCEPTED)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private UserList persistList(UUID ownerId, String name, UserListVisibility visibility) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        return userListRepository.save(UserList.builder()
                .user(owner)
                .name(name)
                .visibility(visibility)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }
}
