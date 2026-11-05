package com.watchwise.watchwise_api.contentposter.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.validation.TmdbPosterPathPolicy;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentposter.entity.UserContentPoster;
import com.watchwise.watchwise_api.contentposter.repository.UserContentPosterRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.TestEmailConfirmationSender;
import com.watchwise.watchwise_api.user.service.TestRegistrationHelper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UserContentPosterControllerIntegrationTest {

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
    private TestEmailConfirmationSender emailConfirmationSender;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private UserContentPosterRepository userContentPosterRepository;

    private Content content;

    @BeforeEach
    void setUp() {
        userContentPosterRepository.deleteAll();
        contentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        content = contentRepository.save(Content.builder()
                .tmdbId("550")
                .type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    @Test
    @DisplayName("[PUT] Should Persist Poster - When Authenticated User Sends A Valid TMDB Path")
    void shouldPersistPosterWhenAuthenticatedUserSendsValidPath() throws Exception {
        RegisteredUser user = registerUser("posters-put");
        String posterPath = posterPath("fight-club.png");

        mockMvc.perform(putRequest(user, content.getId(), posterPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentId").value(content.getId().toString()))
                .andExpect(jsonPath("$.customPosterUrl").value(posterPath))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        assertThat(userContentPosterRepository.findByUserIdAndContentId(user.id(), content.getId()))
                .get()
                .extracting(UserContentPoster::getCustomPosterUrl)
                .isEqualTo(posterPath);
    }

    @Test
    @DisplayName("[PUT] Should Replace Poster - When Same User Sends A Second URL")
    void shouldReplacePosterWhenSameUserSendsSecondUrl() throws Exception {
        RegisteredUser user = registerUser("posters-replace");
        String firstPath = posterPath("first.png");
        String secondPath = posterPath("second.png");

        mockMvc.perform(putRequest(user, content.getId(), firstPath))
                .andExpect(status().isOk());
        mockMvc.perform(putRequest(user, content.getId(), secondPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customPosterUrl").value(secondPath));

        assertThat(userContentPosterRepository.findAll())
                .singleElement()
                .extracting(UserContentPoster::getCustomPosterUrl)
                .isEqualTo(secondPath);
    }

    @Test
    @DisplayName("[DELETE] Should Keep Another User's Poster - When A Different User Deletes The Same Content")
    void shouldKeepAnotherUsersPosterWhenDifferentUserDeletesSameContent() throws Exception {
        RegisteredUser owner = registerUser("posters-owner");
        RegisteredUser otherUser = registerUser("posters-other");
        String posterPath = posterPath("owner.png");

        mockMvc.perform(putRequest(owner, content.getId(), posterPath))
                .andExpect(status().isOk());

        mockMvc.perform(deleteRequest(otherUser, content.getId()))
                .andExpect(status().isNoContent());

        assertThat(userContentPosterRepository.findByUserIdAndContentId(owner.id(), content.getId()))
                .isPresent();
        assertThat(userContentPosterRepository.findByUserIdAndContentId(otherUser.id(), content.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("[PUT] Should Return NotFound - When Content Reference Does Not Exist")
    void shouldReturnNotFoundWhenContentReferenceDoesNotExist() throws Exception {
        RegisteredUser user = registerUser("posters-unknown-content");

        mockMvc.perform(putRequest(user, UUID.randomUUID(), posterPath("unknown.png")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[PUT] Should Reject Absolute Poster URLs And Empty Paths")
    void shouldRejectAbsolutePosterUrlsAndEmptyPaths() throws Exception {
        RegisteredUser user = registerUser("posters-invalid-prefix");
        List<String> invalidValues = List.of(
                "http://image.tmdb.org/t/p/w342/poster.png",
                "https://example.com/poster.png",
                "https://image.tmdb.org/t/p/w500/poster.png",
                "https://image.tmdb.org/t/p/original/poster.png",
                TmdbPosterPathPolicy.PATH_PREFIX
        );

        for (String invalidValue : invalidValues) {
            mockMvc.perform(putRequest(user, content.getId(), invalidValue))
                    .andExpect(status().isBadRequest());
        }

        assertThat(userContentPosterRepository.findByUserIdAndContentId(user.id(), content.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("[DELETE] Should Restore TMDB Fallback - When Poster Is Deleted Repeatedly")
    void shouldRestoreTmdbFallbackWhenPosterIsDeletedRepeatedly() throws Exception {
        RegisteredUser user = registerUser("posters-clear");

        mockMvc.perform(putRequest(user, content.getId(), posterPath("clear-me.png")))
                .andExpect(status().isOk());
        mockMvc.perform(deleteRequest(user, content.getId()))
                .andExpect(status().isNoContent());
        mockMvc.perform(deleteRequest(user, content.getId()))
                .andExpect(status().isNoContent());

        assertThat(userContentPosterRepository.findByUserIdAndContentId(user.id(), content.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("[PUT and DELETE] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresent() throws Exception {
        RegisteredUser user = registerUser("posters-noauth");

        mockMvc.perform(put("/users/me/content-posters/{contentId}", content.getId())
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customPosterUrl\":\"" + posterPath("unauthenticated.png") + "\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/users/me/content-posters/{contentId}", content.getId())
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    private RegisteredUser registerUser(String username) throws Exception {
        TestRegistrationHelper.RegisteredTestUser registered = TestRegistrationHelper.register(
                mockMvc, userRepository, emailConfirmationSender,
                username, username, username + "@email.com", true);
        return new RegisteredUser(registered.id(), registered.accessToken(), registered.csrfToken());
    }

    private MockHttpServletRequestBuilder putRequest(RegisteredUser user, UUID contentId, String posterPath) {
        return put("/users/me/content-posters/{contentId}", contentId)
                .cookie(user.accessToken(), user.csrfToken())
                .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customPosterUrl\":\"" + posterPath + "\"}");
    }

    private MockHttpServletRequestBuilder deleteRequest(RegisteredUser user, UUID contentId) {
        return delete("/users/me/content-posters/{contentId}", contentId)
                .cookie(user.accessToken(), user.csrfToken())
                .header("X-XSRF-TOKEN", user.csrfToken().getValue());
    }

    private String posterPath(String suffix) {
        return TmdbPosterPathPolicy.PATH_PREFIX + suffix;
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }
}
