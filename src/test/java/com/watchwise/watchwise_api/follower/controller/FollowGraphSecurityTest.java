package com.watchwise.watchwise_api.follower.controller;

import com.watchwise.watchwise_api.WatchwiseApiApplication;
import com.watchwise.watchwise_api.common.config.CorsConfig;
import com.watchwise.watchwise_api.common.config.SecurityConfig;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.follower.service.FollowerService;
import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FollowerController.class, properties = {
        "spring.docker.compose.enabled=false",
        "spring.flyway.enabled=false",
        "app.cors.allowed-origins=http://localhost:5173",
        "app.rate-limit.follow-action.max-requests=10",
        "app.rate-limit.follow-action.window-minutes=1",
        "app.rate-limit.pick-create.max-requests=10",
        "app.rate-limit.pick-create.window-minutes=1"
})
@Import({SecurityConfig.class, CorsConfig.class})
@ContextConfiguration(classes = WatchwiseApiApplication.class)
class FollowGraphSecurityTest {

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityFilterChain;
    @MockitoBean FollowerService followerService;
    @MockitoBean JwtService jwt;
    @MockitoBean RequestThrottler throttler;
    @MockitoBean UserRepository users;
    @MockitoBean ImportTemporaryFiles temporaryFiles;

    @ParameterizedTest
    @ValueSource(strings = {"followers", "following"})
    void shouldRequireAuthenticationBeforeReadingFollowGraph(String direction) throws Exception {
        MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build()
                .perform(get("/users/{id}/{direction}", UUID.randomUUID(), direction))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(followerService, users);
    }
}
