package com.watchwise.watchwise_api.userblock.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UserBlockControllerIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired UserBlockRepository userBlockRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    @BeforeEach void clean() {
        userBlockRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private record Registered(UUID id, Cookie access, Cookie csrf) {}
    private Registered register(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"%s","name":"%s","email":"%s@email.com","password":"Password123","isProfilePublic":true}
                """.formatted(username, username, username))).andExpect(status().isCreated()).andReturn();
        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new Registered(user.getId(), result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE),
                result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE));
    }
    private MockHttpServletRequestBuilder action(String method, Registered actor, UUID target) {
        MockHttpServletRequestBuilder request = switch (method) {
            case "POST" -> post("/users/me/blocked/" + target);
            case "DELETE" -> delete("/users/me/blocked/" + target);
            default -> throw new IllegalArgumentException(method);
        };
        return request.cookie(actor.access(), actor.csrf()).header("X-XSRF-TOKEN", actor.csrf().getValue());
    }

    @Test void shouldBlockAndUnblockWithNoContentAndMakeRepeatedCommandsIdempotent() throws Exception {
        Registered actor = register("block-controller-a");
        Registered target = register("block-controller-b");
        mockMvc.perform(action("POST", actor, target.id())).andExpect(status().isNoContent());
        mockMvc.perform(action("POST", actor, target.id())).andExpect(status().isNoContent());
        assertThat(userBlockRepository.count()).isEqualTo(1);
        mockMvc.perform(action("DELETE", actor, target.id())).andExpect(status().isNoContent());
        mockMvc.perform(action("DELETE", actor, target.id())).andExpect(status().isNoContent());
        assertThat(userBlockRepository.count()).isZero();
    }

    @Test void shouldRejectSelfBlockAndMissingTarget() throws Exception {
        Registered actor = register("block-controller-self");
        mockMvc.perform(action("POST", actor, actor.id())).andExpect(status().isBadRequest());
        mockMvc.perform(action("POST", actor, UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test void shouldRequireAuthenticationAndCsrfForMutation() throws Exception {
        UUID target = UUID.randomUUID();
        Registered actor = register("block-controller-csrf");
        mockMvc.perform(post("/users/me/blocked/" + target).cookie(actor.csrf())
                        .header("X-XSRF-TOKEN", actor.csrf().getValue()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/users/me/blocked/" + target).cookie(actor.access())).andExpect(status().isForbidden());
        mockMvc.perform(action("POST", actor, target)
                        .headers(headers -> headers.set("X-XSRF-TOKEN", "invalid")))
                .andExpect(status().isForbidden());
    }

    @Test void shouldListBlockedUsersUsingPageResponse() throws Exception {
        Registered actor = register("block-controller-list");
        Registered target = register("block-controller-listed");
        mockMvc.perform(action("POST", actor, target.id())).andExpect(status().isNoContent());
        mockMvc.perform(get("/users/me/blocked").param("page", "1").param("size", "10")
                        .cookie(actor.access()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].user.id").value(target.id().toString()));
    }
}
