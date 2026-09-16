package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateOptionService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PicksTemplateOptionControllerIntegrationTest {

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

    @MockitoBean
    private PicksTemplateOptionService optionService;

    @MockitoBean
    private TmdbClient tmdbClient;

    @MockitoBean(name = "tmdbRestClient")
    private RestClient tmdbRestClient;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        reset(optionService, tmdbClient);
    }

    @Test
    void shouldReturnApiErrorForMalformedTemplateCategoryAndOptionIdentifiers() throws Exception {
        RegisteredUser user = registerUser("optionbindings");
        UUID malformedTemplateCategoryId = UUID.randomUUID();

        mockMvc.perform(get("/picks-templates/not-a-uuid/categories/{categoryId}/options", malformedTemplateCategoryId)
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "Bad Request", "/picks-templates/not-a-uuid/categories/" + malformedTemplateCategoryId + "/options"));
        UUID templateId = UUID.randomUUID();
        mockMvc.perform(get("/picks-templates/{templateId}/categories/not-a-uuid/options", templateId)
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "Bad Request", "/picks-templates/" + templateId + "/categories/not-a-uuid/options"));
        UUID categoryId = UUID.randomUUID();
        mockMvc.perform(delete("/picks-templates/{templateId}/categories/{categoryId}/options/not-a-uuid", templateId, categoryId)
                        .cookie(user.accessToken(), user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "Bad Request", "/picks-templates/" + templateId + "/categories/" + categoryId + "/options/not-a-uuid"));

        verifyNoInteractions(optionService);
    }

    private RegisteredUser registerUser(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s@email.com","password":"Password123"}
                                """.formatted(username, username)))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie accessToken = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfToken = result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessToken).isNotNull();
        assertThat(csrfToken).isNotNull();
        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), accessToken, csrfToken);
    }

    private org.springframework.test.web.servlet.ResultMatcher apiError(int status, String error, String path) {
        return result -> {
            jsonPath("$.status").value(status).match(result);
            jsonPath("$.error").value(error).match(result);
            jsonPath("$.path").value(path).match(result);
            jsonPath("$.detail").doesNotExist().match(result);
            jsonPath("$.title").doesNotExist().match(result);
        };
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }
}
