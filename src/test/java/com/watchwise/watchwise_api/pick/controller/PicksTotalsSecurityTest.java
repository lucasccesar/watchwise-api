package com.watchwise.watchwise_api.pick.controller;

import com.watchwise.watchwise_api.WatchwiseApiApplication;
import com.watchwise.watchwise_api.common.config.CorsConfig;
import com.watchwise.watchwise_api.common.config.SecurityConfig;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.pick.dto.PicksTotalsDTO;
import com.watchwise.watchwise_api.pick.dto.PickAnswerViewDTO;
import com.watchwise.watchwise_api.pick.dto.PickListCardDTO;
import com.watchwise.watchwise_api.pick.dto.PickProgress;
import com.watchwise.watchwise_api.pick.dto.PickViewCardDTO;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.service.MyPicksListService;
import com.watchwise.watchwise_api.pick.service.PickSelectionService;
import com.watchwise.watchwise_api.pick.service.PickService;
import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {PickController.class, MyPicksListController.class}, properties = {
        "spring.docker.compose.enabled=false",
        "spring.flyway.enabled=false",
        "app.cors.allowed-origins=http://localhost:5173",
        "app.rate-limit.pick-create.max-requests=10",
        "app.rate-limit.pick-create.window-minutes=1"
})
@Import({SecurityConfig.class, CorsConfig.class})
@ContextConfiguration(classes = WatchwiseApiApplication.class)
class PicksTotalsSecurityTest {
    private static final String TOTALS = "/users/me/picks/totals";

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityFilterChain;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean MyPicksListService myPicksListService;
    @MockitoBean PickService pickService;
    @MockitoBean PickSelectionService selectionService;
    @MockitoBean JwtService jwt;
    @MockitoBean UserRepository users;
    @MockitoBean RequestThrottler throttler;
    @MockitoBean ImportTemporaryFiles temporaryFiles;

    private MockMvc mvc;

    @BeforeEach
    void configureMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();
    }

    @Test
    void requiresAuthenticationBeforeReadingTotals() throws Exception {
        mvc.perform(get(TOTALS)).andExpect(status().isUnauthorized());

        verifyNoInteractions(pickService, users);
    }

    @Test
    void rejectsAnonymousListAndSerializesOnlyEquivalentCardFieldsForTheCookiePrincipal() throws Exception {
        String list = "/users/me/picks/list";
        mvc.perform(get(list)).andExpect(status().isUnauthorized());
        verifyNoInteractions(myPicksListService);

        UUID viewer = UUID.randomUUID();
        String session = "synthetic-list-cookie";
        when(jwt.isTokenValid(session, TokenType.ACCESS)).thenReturn(true);
        when(jwt.extractUserId(session)).thenReturn(viewer);
        when(users.findSessionsInvalidatedAtById(viewer))
                .thenReturn(Optional.of(mock(UserRepository.SessionsInvalidatedAtView.class)));
        var date = LocalDateTime.of(2026, 11, 1, 10, 0);
        UUID pickId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        var page = PageRequest.of(0, 10);
        var template = new PicksTemplatePreviewDTO(UUID.randomUUID(), PickOrigin.COMMUNITY, "Template", "Description", null);
        var answer = new PickAnswerViewDTO(categoryId, "Category", null, 1, null, null, null, null, true, true);
        var legacy = new PickViewCardDTO(pickId, template, null, PickVisibility.PRIVATE, date,
                PickProgress.COMPLETE, 1, 1, List.of(answer), 2, 3, false);
        var summary = new PickListCardDTO(pickId, new PickListCardDTO.Template("Template", null, null),
                PickVisibility.PRIVATE, date, 1, 1, List.of(new PickListCardDTO.AnsweredCategory(categoryId, "Category")), 2, 3);
        when(pickService.getMyPicksView(viewer, 1, 10)).thenReturn(new PageImpl<>(List.of(legacy), page, 1));
        when(myPicksListService.list(viewer, 1, 10)).thenReturn(new PageImpl<>(List.of(summary), page, 1));
        var cookie = new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, session);
        var oldResponse = mvc.perform(get("/users/me/picks/view").param("page", "1").param("size", "10").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var newResponse = mvc.perform(get(list).param("page", "1").param("size", "10").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var before = objectMapper.readTree(oldResponse);
        var after = objectMapper.readTree(newResponse);
        for (String field : List.of("page", "size", "totalElements", "totalPages", "hasNext")) {
            assertThat(after.path(field)).isEqualTo(before.path(field));
        }
        assertThat(after.path("content").size()).isEqualTo(1);
        var oldCard = before.path("content").get(0);
        var card = after.path("content").get(0);
        var fields = List.of("id", "visibility", "createdAt", "answeredCategoriesCount", "totalCategoriesCount",
                "likesCount", "commentsCount");
        for (String field : fields) {
            assertThat(card.path(field)).isEqualTo(oldCard.path(field));
        }
        assertThat(card.size()).isEqualTo(9);
        for (String field : List.of("name", "coverImage", "creator")) {
            assertThat(card.path("picksTemplate").path(field)).isEqualTo(oldCard.path("picksTemplate").path(field));
        }
        var chip = card.path("answeredCategories").get(0);
        assertThat(chip.size()).isEqualTo(2);
        assertThat(chip.path("categoryId").asText()).isEqualTo(categoryId.toString());
        assertThat(chip.path("name").asText()).isEqualTo("Category");
        assertThat(newResponse.length).isLessThan(oldResponse.length);
        System.out.printf("my-picks fixture body bytes: A=%d B=%d%n", oldResponse.length, newResponse.length);
    }

    @Test
    void usesTheCookiePrincipalAndSerializesBothTotalsIncludingPartialResults() throws Exception {
        UUID viewer = UUID.randomUUID();
        String session = "synthetic-access-cookie";
        when(jwt.isTokenValid(session, TokenType.ACCESS)).thenReturn(true);
        when(jwt.extractUserId(session)).thenReturn(viewer);
        when(users.findSessionsInvalidatedAtById(viewer))
                .thenReturn(Optional.of(mock(UserRepository.SessionsInvalidatedAtView.class)));
        when(pickService.getMyTotals(viewer))
                .thenReturn(new PicksTotalsDTO(3L, 2L), new PicksTotalsDTO(null, 2L));

        mvc.perform(get(TOTALS).cookie(new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, session)))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"picksCount\":3,\"templatesCount\":2}"));
        mvc.perform(get(TOTALS).cookie(new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, session)))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"picksCount\":null,\"templatesCount\":2}"));
    }
}
