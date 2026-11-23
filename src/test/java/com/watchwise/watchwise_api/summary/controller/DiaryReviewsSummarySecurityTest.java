package com.watchwise.watchwise_api.summary.controller;

import com.watchwise.watchwise_api.WatchwiseApiApplication;
import com.watchwise.watchwise_api.common.config.CorsConfig;
import com.watchwise.watchwise_api.common.config.SecurityConfig;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.feed.dto.FeedEventType;
import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import com.watchwise.watchwise_api.summary.dto.DiaryReviewsSummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.HomeSocialActivityDTO;
import com.watchwise.watchwise_api.summary.dto.HomeSummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.LatestWatchedBackdropResponseDTO;
import com.watchwise.watchwise_api.summary.service.SummaryService;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SummaryController.class, properties = {
        "spring.docker.compose.enabled=false",
        "spring.flyway.enabled=false",
        "app.cors.allowed-origins=http://localhost:5173",
        "app.rate-limit.pick-create.max-requests=10",
        "app.rate-limit.pick-create.window-minutes=1"
})
@Import({SecurityConfig.class, CorsConfig.class})
@ContextConfiguration(classes = WatchwiseApiApplication.class)
class DiaryReviewsSummarySecurityTest {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityFilterChain;
    @MockitoBean SummaryService summaryService;
    @MockitoBean JwtService jwt;
    @MockitoBean RequestThrottler throttler;
    @MockitoBean UserRepository users;
    @MockitoBean ImportTemporaryFiles temporaryFiles;

    @Test
    void requiresAuthenticationAndPassesTheAuthenticatedViewerToTheDedicatedService() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();
        UUID viewer = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        String route = "/users/{id}/summary/diary-reviews";
        mvc.perform(get(route, target)).andExpect(status().isUnauthorized());
        verifyNoInteractions(summaryService, users);

        String token = "synthetic-reviews-cookie";
        when(jwt.isTokenValid(token, TokenType.ACCESS)).thenReturn(true);
        when(jwt.extractUserId(token)).thenReturn(viewer);
        when(users.findSessionsInvalidatedAtById(viewer))
                .thenReturn(Optional.of(mock(UserRepository.SessionsInvalidatedAtView.class)));
        when(summaryService.getDiaryReviewsSummary(viewer, target))
                .thenReturn(new DiaryReviewsSummaryResponseDTO(11, 12, 13, 14, 15, 16, null));

        mvc.perform(get(route, target).cookie(new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, token)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"totalMinutesWatchedMovies":11,"totalMinutesWatchedEpisodes":12,"totalMoviesWatched":13,
                         "totalDistinctMoviesWatched":14,"totalEpisodesWatched":15,"distinctSeriesWatched":16,
                         "latestWatchedBackdropPath":null}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void shouldBindHomeDiaryOptInWithoutChangingTheDefaultAuthenticatedRoute() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();
        UUID viewer = UUID.randomUUID();
        String route = "/users/{id}/summary/home";
        mvc.perform(get(route, viewer).param("includeDiaryActivity", "true"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(summaryService, users);

        String token = "synthetic-home-cookie";
        when(jwt.isTokenValid(token, TokenType.ACCESS)).thenReturn(true);
        when(jwt.extractUserId(token)).thenReturn(viewer);
        when(users.findSessionsInvalidatedAtById(viewer))
                .thenReturn(Optional.of(mock(UserRepository.SessionsInvalidatedAtView.class)));
        UUID diaryId = UUID.randomUUID();
        LocalDateTime watched = LocalDateTime.of(2026, 11, 2, 7, 52);
        var legacy = new HomeSocialActivityDTO(FeedEventType.DIARY_ENTRY, diaryId, null, null,
                null, 0, 0, watched);
        var optedIn = new HomeSocialActivityDTO(FeedEventType.DIARY_ENTRY, diaryId, null, null,
                null, 0, 0, watched, null, 6, "review", false);
        when(summaryService.getHomeSummary(viewer, viewer, false)).thenReturn(homeWith(legacy));
        when(summaryService.getHomeSummary(viewer, viewer, true)).thenReturn(homeWith(optedIn));

        var cookie = new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, token);
        mvc.perform(get(route, viewer).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.socialActivities[0].likedByMe").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.socialActivities[0].score").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.socialActivities[0].comment").doesNotHaveJsonPath());
        mvc.perform(get(route, viewer).param("includeDiaryActivity", "true").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.socialActivities[0].id").value(diaryId.toString()))
                .andExpect(jsonPath("$.socialActivities[0].likedByMe").value(false))
                .andExpect(jsonPath("$.socialActivities[0].score").value(6))
                .andExpect(jsonPath("$.socialActivities[0].comment").value("review"));
    }

    private HomeSummaryResponseDTO homeWith(HomeSocialActivityDTO activity) {
        return new HomeSummaryResponseDTO(0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(),
                List.of(), List.of(), null, false, List.of(), List.of(activity));
    }

    @Test
    void requiresAuthenticationAndReturnsOnlyTheCurrentUsersBackdrop() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();
        String route = "/users/me/summary/latest-watched-backdrop";
        mvc.perform(get(route)).andExpect(status().isUnauthorized());
        verifyNoInteractions(summaryService, users);

        UUID viewer = UUID.randomUUID();
        String token = "synthetic-backdrop-cookie";
        when(jwt.isTokenValid(token, TokenType.ACCESS)).thenReturn(true);
        when(jwt.extractUserId(token)).thenReturn(viewer);
        when(users.findSessionsInvalidatedAtById(viewer))
                .thenReturn(Optional.of(mock(UserRepository.SessionsInvalidatedAtView.class)));
        when(summaryService.getLatestWatchedBackdrop(viewer)).thenReturn(
                new LatestWatchedBackdropResponseDTO(null), new LatestWatchedBackdropResponseDTO("/custom.jpg"));

        mvc.perform(get(route).cookie(new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, token)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"latestWatchedBackdropPath":null}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        mvc.perform(get(route).cookie(new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, token)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"latestWatchedBackdropPath":"/custom.jpg"}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        verify(summaryService, times(2)).getLatestWatchedBackdrop(viewer);
    }
}
