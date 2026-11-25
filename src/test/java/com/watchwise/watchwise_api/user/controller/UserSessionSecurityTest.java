package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.WatchwiseApiApplication;
import com.watchwise.watchwise_api.common.config.CorsConfig;
import com.watchwise.watchwise_api.common.config.SecurityConfig;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import com.watchwise.watchwise_api.user.dto.SessionIdentityDTO;
import com.watchwise.watchwise_api.user.service.UserService;
import com.watchwise.watchwise_api.settings.service.SettingsViewService;
import com.watchwise.watchwise_api.common.security.AttemptLockout;
import org.springframework.transaction.PlatformTransactionManager;
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

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserController.class, properties = {
        "spring.docker.compose.enabled=false",
        "spring.flyway.enabled=false",
        "app.cors.allowed-origins=http://localhost:5173",
        "app.rate-limit.pick-create.max-requests=10",
        "app.rate-limit.pick-create.window-minutes=1"
})
@Import({SecurityConfig.class, CorsConfig.class})
@ContextConfiguration(classes = WatchwiseApiApplication.class)
class UserSessionSecurityTest {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityFilterChain;
    @MockitoBean UserService userService;
    @MockitoBean SettingsViewService settingsViewService;
    @MockitoBean AttemptLockout attemptLockout;
    @MockitoBean CookieUtil cookieUtil;
    @MockitoBean PlatformTransactionManager transactionManager;
    @MockitoBean JwtService jwt;
    @MockitoBean RequestThrottler throttler;
    @MockitoBean UserRepository users;
    @MockitoBean ImportTemporaryFiles temporaryFiles;

    @Test
    void authenticatesTheCurrentUserBeforeResolvingSessionIdentity() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();
        UUID id = UUID.randomUUID();
        String token = "synthetic-access";
        String route = "/users/me/session";
        mvc.perform(get(route)).andExpect(status().isUnauthorized());
        mvc.perform(get(route).cookie(new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, token)))
                .andExpect(status().isUnauthorized());
        when(jwt.isTokenValid(token, TokenType.ACCESS)).thenReturn(true);
        when(jwt.extractUserId(token)).thenReturn(id);
        when(users.findSessionsInvalidatedAtById(id)).thenReturn(Optional.empty());
        mvc.perform(get(route).cookie(new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, token)))
                .andExpect(status().isUnauthorized());

        var version = mock(UserRepository.SessionsInvalidatedAtView.class);
        when(version.getAuthVersion()).thenReturn(1L);
        when(users.findSessionsInvalidatedAtById(id)).thenReturn(Optional.of(version));
        mvc.perform(get(route).cookie(new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, token)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(userService);

        when(jwt.extractAuthVersion(token)).thenReturn(1L);
        when(userService.getSessionIdentity(id)).thenReturn(new SessionIdentityDTO(id, "viewer",
                "viewer@example.test", "Viewer", null, null, null, false, "pt-BR", "BR"));
        mvc.perform(get(route).cookie(new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, token)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":"%s","username":"viewer","email":"viewer@example.test","name":"Viewer",
                         "description":null,"profilePicture":null,"banner":null,"isProfilePublic":false,
                         "preferredLanguage":"pt-BR","preferredRegion":"BR"}
                        """.formatted(id), org.springframework.test.json.JsonCompareMode.STRICT));
        verify(userService).getSessionIdentity(id);
        verifyNoMoreInteractions(userService);
    }
}
