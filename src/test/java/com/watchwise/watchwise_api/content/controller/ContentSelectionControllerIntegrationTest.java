package com.watchwise.watchwise_api.content.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.content.dto.ContentEpisodeSelectionDTO;
import com.watchwise.watchwise_api.content.dto.ContentSeasonSelectionDTO;
import com.watchwise.watchwise_api.content.service.ContentSelectionService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.TestEmailConfirmationSender;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ContentSelectionControllerIntegrationTest {

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
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TestEmailConfirmationSender emailConfirmationSender;

    @MockitoBean
    private ContentSelectionService contentSelectionService;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        reset(contentSelectionService);
        emailConfirmationSender.clear();
    }

    private record RegisteredUser(UUID id, Cookie accessToken) {
    }

    private RegisteredUser registerUser(String username) throws Exception {
        String email = username + "@email.com";
        MvcResult registration = mockMvc.perform(registerRequest(username))
                .andExpect(status().isAccepted())
                .andReturn();
        Cookie registrationProof = registration.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE);
        MvcResult confirmation = mockMvc.perform(post("/auth/confirm-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + emailConfirmationSender.tokenFor(email) + "\"}")
                        .cookie(registrationProof))
                .andExpect(status().isNoContent())
                .andReturn();
        Cookie accessToken = confirmation.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessToken).isNotNull();
        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), accessToken);
    }

    private MockHttpServletRequestBuilder registerRequest(String username) {
        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","name":"%s","email":"%s@email.com","password":"Password123"}
                        """.formatted(username, username, username));
    }

    @Test
    @DisplayName("[listSeasons] Should Return Seasons And Use The False Default - When Specials Are Omitted")
    void returnsSeasonsAndUsesFalseDefault() throws Exception {
        RegisteredUser user = registerUser("selectionseasons");
        when(contentSelectionService.listSeasons(user.id(), "1399", false))
                .thenReturn(List.of(new ContentSeasonSelectionDTO("1399", 1, "Season 1", 8, null, null)));

        mockMvc.perform(get("/contents/selection/series/1399/seasons").cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seasonNumber").value(1))
                .andExpect(jsonPath("$[0].name").value("Season 1"));

        verify(contentSelectionService).listSeasons(user.id(), "1399", false);
    }

    @Test
    @DisplayName("[listEpisodes] Should Return Specials And Use The True Default - When Unreleased Flag Is Omitted")
    void returnsSpecialsEpisodesAndUsesTrueDefault() throws Exception {
        RegisteredUser user = registerUser("selectionepisodes");
        when(contentSelectionService.listEpisodes(user.id(), "1399", 0, true))
                .thenReturn(List.of(new ContentEpisodeSelectionDTO("1399", 0, 1, "Special", null, null)));

        mockMvc.perform(get("/contents/selection/series/1399/seasons/0/episodes")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seasonNumber").value(0))
                .andExpect(jsonPath("$[0].episodeNumber").value(1));

        verify(contentSelectionService).listEpisodes(user.id(), "1399", 0, true);
    }

    @Test
    @DisplayName("[listSeasons] Should Return ApiError - When The Boolean Query Value Is Invalid")
    void returnsApiErrorForInvalidBooleanQueryValue() throws Exception {
        RegisteredUser user = registerUser("selectioninvalidbool");

        mockMvc.perform(get("/contents/selection/series/1399/seasons")
                        .param("includeSpecials", "sometimes")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/contents/selection/series/1399/seasons"))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    @DisplayName("[listEpisodes] Should Return ApiError - When The Season Path Value Is Invalid")
    void returnsApiErrorForInvalidSeasonPathValue() throws Exception {
        RegisteredUser user = registerUser("selectioninvalidpath");

        mockMvc.perform(get("/contents/selection/series/1399/seasons/not-a-number/episodes")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/contents/selection/series/1399/seasons/not-a-number/episodes"))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    @DisplayName("[listSeasons] Should Require Authentication - When The Route Is Accessed Anonymously")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/contents/selection/series/1399/seasons"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[listSeasons] Should Return A 502 ApiError - When Content Selection Reports TMDB Unavailable")
    void listSeasonsReturns502ApiErrorWhenContentSelectionReportsTmdbUnavailable() throws Exception {
        RegisteredUser user = registerUser("selectiontmdbunavailable");
        when(contentSelectionService.listSeasons(user.id(), "1399", false))
                .thenThrow(new TmdbUnavailableException("TMDB is unavailable"));

        mockMvc.perform(get("/contents/selection/series/1399/seasons").cookie(user.accessToken()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                .andExpect(jsonPath("$.message").value("TMDB is unavailable"))
                .andExpect(jsonPath("$.path").value("/contents/selection/series/1399/seasons"));
    }
}
