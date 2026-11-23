package com.watchwise.watchwise_api.seriesprogress.controller;

import com.watchwise.watchwise_api.WatchwiseApiApplication;
import com.watchwise.watchwise_api.common.config.CorsConfig;
import com.watchwise.watchwise_api.common.config.SecurityConfig;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import com.watchwise.watchwise_api.seriesprogress.dto.IndividualSeriesProgressDTO;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressReader;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.TokenType;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = IndividualSeriesProgressController.class, properties = {
        "spring.docker.compose.enabled=false",
        "spring.flyway.enabled=false",
        "app.cors.allowed-origins=http://localhost:5173",
        "app.rate-limit.pick-create.max-requests=10",
        "app.rate-limit.pick-create.window-minutes=1"
})
@Import({SecurityConfig.class, CorsConfig.class})
@ContextConfiguration(classes = WatchwiseApiApplication.class)
class IndividualSeriesProgressSecurityTest {

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityFilterChain;
    @MockitoBean SeriesProgressReader reader;
    @MockitoBean JwtService jwt;
    @MockitoBean RequestThrottler throttler;
    @MockitoBean UserRepository users;
    @MockitoBean ImportTemporaryFiles temporaryFiles;

    private static final String PATH = "/users/me/series-progress/1396";

    @Test
    void rejectsAnonymousAndOffersNoRouteToAnotherUsersProgress() throws Exception {
        mvc().perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc().perform(get("/users/{id}/series-progress/1396", UUID.randomUUID())
                        .cookie(authenticated(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        verifyNoInteractions(reader);
    }

    @Test
    void readsOnlyTheAuthenticatedUsersProgressAndReturnsNoContentForAbsence() throws Exception {
        UUID viewer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var progress = new IndividualSeriesProgressDTO(2L, 5, 5, 40.0, 3L,
                LocalDate.of(2024, 3, 2), 2, List.of(new IndividualSeriesProgressDTO.Season(2, 1L, 2)));
        when(reader.readStartedSeries(viewer, "1396")).thenReturn(Optional.of(progress));

        mvc().perform(get(PATH).param("userId", other.toString()).cookie(authenticated(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchedEpisodeCount").value(2))
                .andExpect(jsonPath("$.remainingEpisodeCount").value(3))
                .andExpect(jsonPath("$.lastWatchedDate").value("2024-03-02"))
                .andExpect(jsonPath("$.seasonProgress[0].seasonNumber").value(2));
        mvc().perform(get(PATH).cookie(authenticated(other))).andExpect(status().isNoContent());
        verify(reader).readStartedSeries(viewer, "1396");
        verify(reader).readStartedSeries(other, "1396");
    }

    private org.springframework.test.web.servlet.MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();
    }

    private Cookie authenticated(UUID userId) {
        String token = "synthetic-" + userId;
        var version = mock(UserRepository.SessionsInvalidatedAtView.class);
        when(version.getAuthVersion()).thenReturn(1L);
        when(users.findSessionsInvalidatedAtById(userId)).thenReturn(Optional.of(version));
        when(jwt.isTokenValid(token, TokenType.ACCESS)).thenReturn(true);
        when(jwt.extractUserId(token)).thenReturn(userId);
        when(jwt.extractAuthVersion(token)).thenReturn(1L);
        return new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, token);
    }
}
