package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.WatchwiseApiApplication;
import com.watchwise.watchwise_api.common.config.CorsConfig;
import com.watchwise.watchwise_api.common.config.SecurityConfig;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.impl.ProfileHeaderReader;
import com.watchwise.watchwise_api.userblock.entity.UserBlockRelationship;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProfileHeaderController.class, properties = {
        "spring.docker.compose.enabled=false", "spring.flyway.enabled=false",
        "app.cors.allowed-origins=http://localhost:5173",
        "app.rate-limit.pick-create.max-requests=10", "app.rate-limit.pick-create.window-minutes=1",
        "app.rate-limit.profile-scan.max-requests=30", "app.rate-limit.profile-scan.window-minutes=5"
})
@Import({SecurityConfig.class, CorsConfig.class, ProfileHeaderReader.class})
@ContextConfiguration(classes = WatchwiseApiApplication.class)
class ProfileHeaderSecurityTest {
    private static final String PATH = "/users/profile-header";
    private final UUID viewer = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityFilterChain;
    @MockitoBean UserRepository users;
    @MockitoBean FollowerRepository followers;
    @MockitoBean DiaryEntryRepository diary;
    @MockitoBean UserBlockAccessService blocks;
    @MockitoBean RequestThrottler throttler;
    @MockitoBean JwtService jwt;
    @MockitoBean ImportTemporaryFiles temporaryFiles;

    @Test
    void anonymousCannotReadAndAnInexactOrUnknownNameIsNotFound() throws Exception {
        mvc().perform(get(PATH).param("username", "abc")).andExpect(status().isUnauthorized());
        verifyNoInteractions(users, followers, diary, blocks);
        mvc().perform(get(PATH).param("username", "abc").cookie(authenticated()))
                .andExpect(status().isNotFound());
        mvc().perform(get(PATH).param("username", " ").cookie(authenticated()))
                .andExpect(status().isBadRequest());
        mvc().perform(get(PATH).param("username", " abc ").cookie(authenticated()))
                .andExpect(status().isNotFound());
        verify(users).findProfileHeaderIdentityByUsername("abc");
        verifyNoInteractions(followers, diary, blocks);
    }

    @Test
    void exactCaseInsensitiveLookupUsesAuthenticatedViewerAndOnlyRecentMinutesForOwnPrivateProfile() throws Exception {
        lookup("ÉXAct", viewer, false);
        LocalDate today = LocalDate.now();
        when(diary.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(viewer, ContentType.MOVIE, today.minusDays(30), today)).thenReturn(95L);
        when(diary.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(viewer, ContentType.EPISODE, today.minusDays(30), today)).thenReturn(43L);

        mvc().perform(get(PATH).param("username", "@ÉXAct").param("viewerId", target.toString()).cookie(authenticated()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(viewer.toString()))
                .andExpect(jsonPath("$.username").value("éxact"))
                .andExpect(jsonPath("$.access").value("VISIBLE"))
                .andExpect(jsonPath("$.ownedByViewer").value(true))
                .andExpect(jsonPath("$.minutesWatchedMoviesLast30Days").value(95))
                .andExpect(jsonPath("$.minutesWatchedEpisodesLast30Days").value(43))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.totalMinutesWatchedMovies").doesNotExist())
                .andExpect(jsonPath("$.genreCountsMovies").doesNotExist())
                .andExpect(jsonPath("$.totalTheaterVisits").doesNotExist());
        verify(throttler).checkAllowed("profile-scan|" + viewer, 30, Duration.ofMinutes(5));
        verify(diary).sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(viewer, ContentType.MOVIE, today.minusDays(30), today);
        verify(diary).sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(viewer, ContentType.EPISODE, today.minusDays(30), today);
        verifyNoMoreInteractions(diary);
    }

    @Test
    void pendingPrivateExposesOnlyAllowedHeaderAndKeepsFollowState() throws Exception {
        lookup("éxact", target, false);
        relations(FollowStatus.PENDING);
        mvc().perform(get(PATH).param("username", "éxact").cookie(authenticated()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access").value("PRIVATE"))
                .andExpect(jsonPath("$.viewerFollowStatus").value("PENDING"))
                .andExpect(jsonPath("$.followsViewer").value(true))
                .andExpect(jsonPath("$.description").value("Bio"))
                .andExpect(jsonPath("$.minutesWatchedMoviesLast30Days").doesNotExist());
        verifyNoInteractions(diary);
    }

    @Test
    void acceptedPrivateAndPublicWithoutFollowAreVisible() throws Exception {
        lookup("éxact", target, false);
        relations(FollowStatus.ACCEPTED);
        mvc().perform(get(PATH).param("username", "éxact").cookie(authenticated()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access").value("VISIBLE"))
                .andExpect(jsonPath("$.viewerFollowStatus").value("ACCEPTED"));
        lookup("éxact", target, true);
        when(followers.findViewerRelations(viewer, List.of(target))).thenReturn(List.of());
        mvc().perform(get(PATH).param("username", "éxact").cookie(authenticated()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access").value("VISIBLE"))
                .andExpect(jsonPath("$.ownedByViewer").value(false))
                .andExpect(jsonPath("$.followsViewer").value(false));
    }

    @ParameterizedTest
    @EnumSource(value = UserBlockRelationship.class, names = {"BLOCKED_BY_ME", "BLOCKED_ME", "MUTUAL"})
    void blockedHeadersStayMaskedIncludingPublicTargets(UserBlockRelationship direction) throws Exception {
        for (boolean isPublic : List.of(false, true)) {
            lookup("éxact", target, isPublic);
            when(blocks.relationship(viewer, target)).thenReturn(direction);
            mvc().perform(get(PATH).param("username", "éxact").cookie(authenticated()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.access").value(isPublic ? "UNAVAILABLE" : "PRIVATE"))
                    .andExpect(jsonPath("$.blockRelationship").value(direction.name()))
                    .andExpect(jsonPath("$.description").doesNotExist())
                    .andExpect(jsonPath("$.profilePicture").doesNotExist())
                    .andExpect(jsonPath("$.banner").doesNotExist())
                    .andExpect(jsonPath("$.isProfilePublic").doesNotExist())
                    .andExpect(jsonPath("$.followersCount").doesNotExist())
                    .andExpect(jsonPath("$.viewerFollowStatus").doesNotExist())
                    .andExpect(jsonPath("$.minutesWatchedMoviesLast30Days").doesNotExist());
        }
        verifyNoInteractions(diary);
    }

    private void lookup(String lookup, UUID id, boolean isPublic) {
        var row = mock(UserRepository.ProfileHeaderIdentity.class);
        when(row.getId()).thenReturn(id);
        when(row.getUsername()).thenReturn("éxact");
        when(row.getName()).thenReturn("Name");
        when(row.getDescription()).thenReturn("Bio");
        when(row.getProfilePicture()).thenReturn("/avatar");
        when(row.getBanner()).thenReturn("/banner");
        when(row.getIsProfilePublic()).thenReturn(isPublic);
        when(users.findProfileHeaderIdentityByUsername(lookup)).thenReturn(Optional.of(row));
        when(blocks.relationship(viewer, id)).thenReturn(UserBlockRelationship.NONE);
    }

    private void relations(FollowStatus status) {
        var outgoing = mock(FollowerRepository.ViewerRelationRow.class);
        when(outgoing.getFollowerId()).thenReturn(viewer);
        when(outgoing.getStatus()).thenReturn(status);
        var incoming = mock(FollowerRepository.ViewerRelationRow.class);
        when(incoming.getFollowerId()).thenReturn(target);
        when(incoming.getStatus()).thenReturn(FollowStatus.ACCEPTED);
        when(followers.findViewerRelations(viewer, List.of(target))).thenReturn(List.of(outgoing, incoming));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();
    }

    private Cookie authenticated() {
        String token = "header-test";
        var version = mock(UserRepository.SessionsInvalidatedAtView.class);
        when(version.getAuthVersion()).thenReturn(1L);
        when(users.findSessionsInvalidatedAtById(viewer)).thenReturn(Optional.of(version));
        when(jwt.isTokenValid(token, TokenType.ACCESS)).thenReturn(true);
        when(jwt.extractUserId(token)).thenReturn(viewer);
        when(jwt.extractAuthVersion(token)).thenReturn(1L);
        return new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, token);
    }
}

