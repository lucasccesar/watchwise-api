package com.watchwise.watchwise_api.pick.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import com.watchwise.watchwise_api.pick.dto.PickPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickSort;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.service.PickSelectionService;
import com.watchwise.watchwise_api.pick.service.PickService;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateCategoryService;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
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
class PickControllerIntegrationTest {

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
    private RequestThrottler requestThrottler;

    @MockitoBean
    private PickService pickService;

    @MockitoBean
    private PickSelectionService pickSelectionService;

    @MockitoBean
    private PicksTemplateService picksTemplateService;

    @MockitoBean
    private PicksTemplateCategoryService picksTemplateCategoryService;

    @MockitoBean
    private TmdbClient tmdbClient;

    @MockitoBean(name = "tmdbRestClient")
    private RestClient tmdbRestClient;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        reset(pickService, pickSelectionService, picksTemplateService, picksTemplateCategoryService, tmdbClient);
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    @Test
    void shouldThrottleTheThirdPickCreationAndKeepBucketsIndependentPerUser() throws Exception {
        RegisteredUser userA = registerUser("pickthrottlea");
        RegisteredUser userB = registerUser("pickthrottleb");
        UUID templateId = UUID.randomUUID();
        when(pickService.createPick(any(), eq(templateId), any())).thenAnswer(invocation -> response((UUID) invocation.getArgument(0)));

        mockMvc.perform(createPickRequest(userA, templateId))
                .andExpect(status().isCreated());
        mockMvc.perform(createPickRequest(userA, templateId))
                .andExpect(status().isCreated());
        mockMvc.perform(createPickRequest(userA, templateId))
                .andExpect(status().isTooManyRequests())
                .andExpect(apiError(429, "Too Many Requests", "/picks-templates/" + templateId + "/picks"));

        mockMvc.perform(createPickRequest(userB, templateId))
                .andExpect(status().isCreated());

        verify(pickService, times(2)).createPick(eq(userA.id()), eq(templateId), any());
        verify(pickService).createPick(eq(userB.id()), eq(templateId), any());
    }

    @Test
    void shouldReturnApiErrorForPickBindingFailuresAndRejectMissingOrInvalidCsrf() throws Exception {
        RegisteredUser bindingUser = registerUser("pickbindings");
        RegisteredUser mediaTypeUser = registerUser("pickmediatype");
        RegisteredUser csrfUser = registerUser("pickcsrf");
        UUID templateId = UUID.randomUUID();

        mockMvc.perform(createPickRequest(bindingUser, templateId).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "Bad Request", "/picks-templates/" + templateId + "/picks"));
        mockMvc.perform(createPickRequest(bindingUser, templateId).content("""
                {"visibility":"UNKNOWN","selections":[{"categoryId":"%s","target":{"content":{"type":"MOVIE","tmdbId":"550"}}}]}
                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Accepted values: PUBLIC, FOLLOWERS, PRIVATE")))
                .andExpect(apiError(400, "Bad Request", "/picks-templates/" + templateId + "/picks"));
        mockMvc.perform(createPickRequest(bindingUser, templateId))
                .andExpect(status().isTooManyRequests())
                .andExpect(apiError(429, "Too Many Requests", "/picks-templates/" + templateId + "/picks"));

        mockMvc.perform(createPickRequest(mediaTypeUser, templateId).contentType(MediaType.TEXT_PLAIN))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(apiError(415, "Unsupported Media Type", "/picks-templates/" + templateId + "/picks"));
        mockMvc.perform(createPickRequest(mediaTypeUser, templateId).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "Bad Request", "/picks-templates/" + templateId + "/picks"));
        mockMvc.perform(createPickRequest(mediaTypeUser, templateId))
                .andExpect(status().isTooManyRequests())
                .andExpect(apiError(429, "Too Many Requests", "/picks-templates/" + templateId + "/picks"));

        mockMvc.perform(post("/picks-templates/{templateId}/picks", templateId)
                        .cookie(csrfUser.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPickBody()))
                .andExpect(status().isForbidden())
                .andExpect(apiError(403, "Forbidden", "/picks-templates/" + templateId + "/picks"));
        mockMvc.perform(createPickRequest(csrfUser, templateId).headers(headers -> headers.set("X-XSRF-TOKEN", "invalid")))
                .andExpect(status().isForbidden())
                .andExpect(apiError(403, "Forbidden", "/picks-templates/" + templateId + "/picks"));
        mockMvc.perform(createPickRequest(csrfUser, templateId))
                .andExpect(status().isTooManyRequests())
                .andExpect(apiError(429, "Too Many Requests", "/picks-templates/" + templateId + "/picks"));

        verifyNoInteractions(pickService);
    }

    @Test
    void shouldReturnApiErrorForPickTemplateEnumAndUuidBindingFailures() throws Exception {
        RegisteredUser user = registerUser("picktemplatebindings");
        UUID templateId = UUID.randomUUID();

        mockMvc.perform(get("/picks-templates").param("origin", "UNKNOWN").cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Accepted values: OFFICIAL, COMMUNITY")))
                .andExpect(apiError(400, "Bad Request", "/picks-templates"));
        mockMvc.perform(createCategoryRequest(user, templateId, "UNKNOWN", "FIXED"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "Bad Request", "/picks-templates/" + templateId + "/categories"));
        mockMvc.perform(createCategoryRequest(user, templateId, "MOVIE", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "Bad Request", "/picks-templates/" + templateId + "/categories"));
        mockMvc.perform(get("/picks/not-a-uuid").cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "Bad Request", "/picks/not-a-uuid"));
        mockMvc.perform(get("/picks-templates/{templateId}/picks", templateId)
                        .param("sort", "UNKNOWN").cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Accepted values: POPULAR, RECENT")))
                .andExpect(apiError(400, "Bad Request", "/picks-templates/" + templateId + "/picks"));

        verifyNoInteractions(picksTemplateService, picksTemplateCategoryService, pickService);
    }

    @Test
    void shouldReturnPagedVisibleTemplatePicksForDefaultRecentAndPopularSorts() throws Exception {
        RegisteredUser viewer = registerUser("templatereads");
        UUID templateId = UUID.randomUUID();
        UUID publicPickId = UUID.randomUUID();
        UUID followerPickId = UUID.randomUUID();
        UUID privatePickId = UUID.randomUUID();
        PickPreviewDTO publicPick = preview(publicPickId);
        PickPreviewDTO followerPick = preview(followerPickId);
        PickPreviewDTO privatePick = preview(privatePickId);

        when(pickService.getTemplatePicks(viewer.id(), templateId, null, null, null))
                .thenReturn(new PageImpl<>(List.of(publicPick, followerPick), PageRequest.of(0, 20), 2));
        when(pickService.getTemplatePicks(viewer.id(), templateId, PickSort.POPULAR, 1, 1))
                .thenReturn(new PageImpl<>(List.of(followerPick), PageRequest.of(0, 1), 2));

        mockMvc.perform(get("/picks-templates/{templateId}/picks", templateId).cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(publicPickId.toString()))
                .andExpect(jsonPath("$.content[1].id").value(followerPickId.toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2));

        String visibleBody = mockMvc.perform(get("/picks-templates/{templateId}/picks", templateId)
                        .param("sort", "POPULAR").param("page", "1").param("size", "1")
                        .cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(followerPickId.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(visibleBody).doesNotContain(privatePick.id().toString());

        verify(pickService).getTemplatePicks(viewer.id(), templateId, null, null, null);
        verify(pickService).getTemplatePicks(viewer.id(), templateId, PickSort.POPULAR, 1, 1);
    }

    @Test
    void shouldReturnNotFoundForUnknownTemplatePicks() throws Exception {
        RegisteredUser viewer = registerUser("unknownpicktemplate");
        UUID templateId = UUID.randomUUID();
        when(pickService.getTemplatePicks(viewer.id(), templateId, null, null, null))
                .thenThrow(new NotFoundException("Picks template not found"));

        mockMvc.perform(get("/picks-templates/{templateId}/picks", templateId).cookie(viewer.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(apiError(404, "Not Found", "/picks-templates/" + templateId + "/picks"));
    }

    private RegisteredUser registerUser(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
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

    private MockHttpServletRequestBuilder createPickRequest(RegisteredUser user, UUID templateId) {
        return post("/picks-templates/{templateId}/picks", templateId)
                .cookie(user.accessToken(), user.csrfToken())
                .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPickBody());
    }

    private MockHttpServletRequestBuilder createCategoryRequest(RegisteredUser user, UUID templateId,
                                                                 String allowedType, String optionMode) {
        return post("/picks-templates/{templateId}/categories", templateId)
                .cookie(user.accessToken(), user.csrfToken())
                .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Best movie","group":"PRIMARY","displayOrder":1,"allowedType":"%s","optionMode":"%s"}
                        """.formatted(allowedType, optionMode));
    }

    private String validPickBody() {
        return """
                {"visibility":"PUBLIC","selections":[{"categoryId":"%s","target":{"content":{"type":"MOVIE","tmdbId":"550"}}}]}
                """.formatted(UUID.randomUUID());
    }

    private PickResponseDTO response(UUID userId) {
        LocalDateTime now = LocalDateTime.now();
        return new PickResponseDTO(UUID.randomUUID(), null, userId, PickVisibility.PUBLIC, now, now, null, List.of());
    }

    private PickPreviewDTO preview(UUID pickId) {
        return new PickPreviewDTO(pickId, null, PickVisibility.PUBLIC, LocalDateTime.now(), 0, 0, false, List.of());
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
