package com.watchwise.watchwise_api.calendar.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.calendar.dto.CalendarResponseDTO;
import com.watchwise.watchwise_api.calendar.service.CalendarService;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
class CalendarControllerIntegrationTest {

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
    private CalendarService calendarService;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        reset(calendarService);
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    private record RegisteredUser(UUID id, Cookie accessToken) {
    }

    private RegisteredUser registerUser(String username) throws Exception {
        MvcResult result = mockMvc.perform(registerRequest(username))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessToken = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessToken).isNotNull();

        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), accessToken);
    }

    private MockHttpServletRequestBuilder registerRequest(String username) {
        String body = """
                {
                    "username": "%s",
                    "email": "%s@email.com",
                    "password": "Password123"
                }
                """.formatted(username, username);

        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    @DisplayName("[getMonth] Should Return The Calendar And Delegate The Parsed Month - When Authenticated")
    void shouldReturnTheCalendarAndDelegateTheParsedMonthWhenAuthenticated() throws Exception {
        RegisteredUser user = registerUser("calendarvalid");
        YearMonth month = YearMonth.of(2026, 9);
        CalendarResponseDTO expected = new CalendarResponseDTO(month, "BR", List.of());
        when(calendarService.getMonth(user.id(), month)).thenReturn(expected);

        mockMvc.perform(get("/users/me/calendar")
                        .param("month", "2026-09")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-09"))
                .andExpect(jsonPath("$.region").value("BR"))
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.events").isEmpty());

        verify(calendarService).getMonth(user.id(), month);
    }

    @Test
    @DisplayName("[getMonth] Should Return BadRequest ApiError - When Month Is Missing")
    void shouldReturnBadRequestApiErrorWhenMonthIsMissing() throws Exception {
        RegisteredUser user = registerUser("calendarmissingmonth");

        mockMvc.perform(get("/users/me/calendar").cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Required parameter 'month' is missing"))
                .andExpect(jsonPath("$.path").value("/users/me/calendar"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verifyNoInteractions(calendarService);
    }

    @Test
    @DisplayName("[getMonth] Should Return BadRequest ApiError - When Month Is Malformed")
    void shouldReturnBadRequestApiErrorWhenMonthIsMalformed() throws Exception {
        RegisteredUser user = registerUser("calendarbadmonth");

        mockMvc.perform(get("/users/me/calendar")
                        .param("month", "2026-13")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("month must be in YYYY-MM format"))
                .andExpect(jsonPath("$.path").value("/users/me/calendar"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verifyNoInteractions(calendarService);
    }

    @Test
    @DisplayName("[getMonth] Should Return Unauthorized ApiError - When Access Token Is Missing")
    void shouldReturnUnauthorizedApiErrorWhenAccessTokenIsMissing() throws Exception {
        mockMvc.perform(get("/users/me/calendar").param("month", "2026-09"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"))
                .andExpect(jsonPath("$.path").value("/users/me/calendar"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verifyNoInteractions(calendarService);
    }

    @Test
    @DisplayName("[getMonth] Should Return BadGateway ApiError - When TMDB Is Unavailable")
    void shouldReturnBadGatewayApiErrorWhenTmdbIsUnavailable() throws Exception {
        RegisteredUser user = registerUser("calendartmdbdown");
        YearMonth month = YearMonth.of(2026, 9);
        when(calendarService.getMonth(user.id(), month))
                .thenThrow(new TmdbUnavailableException("TMDB is currently unavailable"));

        mockMvc.perform(get("/users/me/calendar")
                        .param("month", "2026-09")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                .andExpect(jsonPath("$.message").value("TMDB is currently unavailable"))
                .andExpect(jsonPath("$.path").value("/users/me/calendar"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verify(calendarService).getMonth(user.id(), month);
    }
}
