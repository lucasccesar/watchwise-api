package com.watchwise.watchwise_api.user.service;

import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Local-only integration-test registration flow; uses the fake sender and never performs network I/O. */
public final class TestRegistrationHelper {

    private TestRegistrationHelper() {
    }

    public static RegisteredTestUser register(
            MockMvc mockMvc,
            UserRepository users,
            TestEmailConfirmationSender sender,
            String username,
            String name,
            String email,
            boolean isProfilePublic
    ) throws Exception {
        String body = """
                {
                    "username": "%s",
                    "name": "%s",
                    "email": "%s",
                    "password": "Password123",
                    "isProfilePublic": %s
                }
                """.formatted(username, name, email, isProfilePublic);

        MvcResult registration = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();
        Cookie proof = registration.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE);
        assertThat(proof).isNotNull();

        String token = sender.tokenFor(email);
        MvcResult confirmation = mockMvc.perform(post("/auth/confirm-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(token))
                        .cookie(proof))
                .andExpect(status().isNoContent())
                .andReturn();

        Cookie accessToken = confirmation.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfToken = confirmation.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessToken).isNotNull();
        assertThat(csrfToken).isNotNull();
        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        return new RegisteredTestUser(user.getId(), accessToken, csrfToken);
    }

    public record RegisteredTestUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }
}
