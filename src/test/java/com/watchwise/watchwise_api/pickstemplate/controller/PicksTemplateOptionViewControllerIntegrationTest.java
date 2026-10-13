package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pick.dto.PickOptionViewDTO;
import com.watchwise.watchwise_api.pick.dto.PickPersonPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetPreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateOptionService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PicksTemplateOptionViewControllerIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @MockitoBean PicksTemplateOptionService optionService;
    @MockitoBean TmdbClient tmdbClient;
    @MockitoBean(name = "tmdbRestClient") RestClient tmdbRestClient;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        reset(optionService, tmdbClient);
    }

    @Test
    @DisplayName("[getOptionViews] Should Return Visual Option Pages With Null IDs - When The Authenticated Viewer Searches Open Options")
    void shouldReturnPaginatedVisualOptionsFromOptionsViewRoute() throws Exception {
        RegisteredUser user = registerUser("optionview");
        UUID templateId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        PickOptionSearchDTO target = new PickOptionSearchDTO(null, null, "42", null);
        PickOptionViewDTO option = new PickOptionViewDTO(null, target,
                new PickTargetPreviewDTO(null, new PickPersonPreviewDTO("42", "Ada", null,
                        ContentPreviewStatus.PARTIAL), null));
        when(optionService.getOptionViews(user.id(), templateId, categoryId, "Ada", null, null, 2, 3))
                .thenReturn(new PageImpl<>(List.of(option), PageRequest.of(1, 3), 4));

        mockMvc.perform(get("/picks-templates/{templateId}/categories/{categoryId}/options/view", templateId, categoryId)
                        .cookie(user.accessToken())
                        .queryParam("q", "Ada")
                        .queryParam("page", "2")
                        .queryParam("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].id").hasJsonPath())
                .andExpect(jsonPath("$.content[0].id").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[0].target.id").hasJsonPath())
                .andExpect(jsonPath("$.content[0].target.id").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[0].target.personTmdbId").value("42"))
                .andExpect(jsonPath("$.content[0].preview.person.name").value("Ada"))
                .andExpect(jsonPath("$.content[0].preview.person.previewStatus").value("PARTIAL"));

        verify(optionService).getOptionViews(user.id(), templateId, categoryId, "Ada", null, null, 2, 3);
    }

    @Test
    @DisplayName("[getOptionViews] Should Keep Visual Cap Separate From Raw Pagination - When Client Requests Size Twenty-One")
    void shouldKeepVisualPageCapSeparateFromRawOptionPaginationWhenClientRequestsSizeTwentyOne() throws Exception {
        RegisteredUser user = registerUser("optionpagecap");
        UUID templateId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        PickOptionSearchDTO target = new PickOptionSearchDTO(null, null, "42", null);
        PickOptionViewDTO visualOption = new PickOptionViewDTO(null, target, null);

        when(optionService.getOptionViews(user.id(), templateId, categoryId, "Ada", null, null, 1, 21))
                .thenReturn(new PageImpl<>(List.of(visualOption), PageRequest.of(0, 20), 21));
        when(optionService.searchOptions(user.id(), templateId, categoryId, "Ada", null, null, 1, 21))
                .thenReturn(new PageImpl<>(List.of(target), PageRequest.of(0, 21), 21));

        mockMvc.perform(get("/picks-templates/{templateId}/categories/{categoryId}/options/view", templateId, categoryId)
                        .cookie(user.accessToken())
                        .queryParam("q", "Ada")
                        .queryParam("page", "1")
                        .queryParam("size", "21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(21));

        mockMvc.perform(get("/picks-templates/{templateId}/categories/{categoryId}/options", templateId, categoryId)
                        .cookie(user.accessToken())
                        .queryParam("q", "Ada")
                        .queryParam("page", "1")
                        .queryParam("size", "21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(21))
                .andExpect(jsonPath("$.totalElements").value(21))
                .andExpect(jsonPath("$.content[0].preview").doesNotExist());

        verify(optionService).getOptionViews(user.id(), templateId, categoryId, "Ada", null, null, 1, 21);
        verify(optionService).searchOptions(user.id(), templateId, categoryId, "Ada", null, null, 1, 21);
    }

    @Test
    @DisplayName("[getOptionViews] Should Require Authentication - When Options View Is Requested Anonymously")
    void shouldRequireAuthenticationForOptionsViewRoute() throws Exception {
        mockMvc.perform(get("/picks-templates/{templateId}/categories/{categoryId}/options/view",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(optionService);
    }

    @Test
    @DisplayName("[searchOptions] Should Retain Raw Option Responses - When The Existing Options Route Is Requested")
    void shouldLeaveRawOptionsRouteDelegatingToRawServiceMethod() throws Exception {
        RegisteredUser user = registerUser("optionraw");
        UUID templateId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(optionService.searchOptions(user.id(), templateId, categoryId, "Ada", null, null, null, null))
                .thenReturn(new PageImpl<>(List.of(new PickOptionSearchDTO(null, null, "42", null)),
                        PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/picks-templates/{templateId}/categories/{categoryId}/options", templateId, categoryId)
                        .cookie(user.accessToken())
                        .queryParam("q", "Ada"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].personTmdbId").value("42"))
                .andExpect(jsonPath("$.content[0].preview").doesNotExist());

        verify(optionService).searchOptions(user.id(), templateId, categoryId, "Ada", null, null, null, null);
        verifyNoInteractions(tmdbClient);
    }

    private RegisteredUser registerUser(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","name":"%s","email":"%s@email.com","password":"Password123"}
                                """.formatted(username, username, username)))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie accessToken = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfToken = result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), accessToken, csrfToken);
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {}
}
