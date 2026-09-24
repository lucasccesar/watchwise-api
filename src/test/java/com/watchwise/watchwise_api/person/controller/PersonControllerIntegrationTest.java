package com.watchwise.watchwise_api.person.controller;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.person.dto.PersonResponseDTO;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;
import com.watchwise.watchwise_api.person.service.PersonService;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.person.dto.PersonDetailsDTO;
import com.watchwise.watchwise_api.person.dto.PersonProgressDTO;
import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
class PersonControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private PersonService personService;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        reset(personService, userRepository);
    }

    @Test
    void shouldReturnPersonResponseWhenAuthenticated() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);
        PersonResponseDTO expected = new PersonResponseDTO(
                new PersonDetailsDTO("12345", "Ada Example", "Biography", null, null, null,
                        null, null, "Acting", java.util.List.of(), false),
                new PersonProgressDTO(0, 0, 0.0),
                new PageResponseDTO<>(java.util.List.of(), 1, 5, 0, 0, false));
        when(personService.getPerson(viewerId, "12345", PersonParticipation.CREW, 1, 5)).thenReturn(expected);

        mockMvc.perform(get("/people/12345")
                        .param("participation", "CREW")
                        .param("page", "1")
                        .param("size", "5")
                        .cookie(accessCookie(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.person.tmdbId").value("12345"))
                .andExpect(jsonPath("$.person.name").value("Ada Example"))
                .andExpect(jsonPath("$.progress.totalCredits").value(0))
                .andExpect(jsonPath("$.credits.page").value(1))
                .andExpect(jsonPath("$.credits.size").value(5));

        verify(personService).getPerson(viewerId, "12345", PersonParticipation.CREW, 1, 5);
    }

    @Test
    void shouldReturnUnauthorizedApiErrorWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/people/12345"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.path").value("/people/12345"));
        verifyNoInteractions(personService);
    }

    @Test
    void shouldReturnBadRequestWhenPersonIdIsNotNumeric() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);
        mockMvc.perform(get("/people/abc").cookie(accessCookie(viewerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("personTmdbId must be a numeric TMDB id up to 20 digits"))
                .andExpect(jsonPath("$.path").value("/people/abc"));

        verify(personService).getPerson(viewerId, "abc", PersonParticipation.ALL, null, null);
    }

    @Test
    void shouldReturnBadRequestWhenParticipationIsInvalid() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);

        mockMvc.perform(get("/people/12345").param("participation", "INVALID").cookie(accessCookie(viewerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Invalid value 'INVALID' for parameter 'participation'. Expected type: PersonParticipation. Accepted values: ALL, CAST, CREW"))
                .andExpect(jsonPath("$.path").value("/people/12345"));

        verifyNoInteractions(personService);
    }

    @Test
    void shouldReturnBadRequestWhenPageOrSizeIsInvalid() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);
        when(personService.getPerson(viewerId, "12345", PersonParticipation.ALL, -1, 0))
                .thenThrow(new BadRequestException("Page number must be greater than or equal to 0"));

        mockMvc.perform(get("/people/12345").param("page", "-1").param("size", "0").cookie(accessCookie(viewerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Page number must be greater than or equal to 0"));

        verify(personService).getPerson(viewerId, "12345", PersonParticipation.ALL, -1, 0);
    }

    @Test
    void shouldReturnNotFoundWhenTmdbPersonDoesNotExist() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);
        when(personService.getPerson(viewerId, "12345", PersonParticipation.ALL, null, null))
                .thenThrow(new NotFoundException("No person found on TMDB for the given id"));

        mockMvc.perform(get("/people/12345").cookie(accessCookie(viewerId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("No person found on TMDB for the given id"));
    }

    @Test
    void shouldReturnBadGatewayWhenTmdbIsUnavailable() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);
        when(personService.getPerson(viewerId, "12345", PersonParticipation.ALL, null, null))
                .thenThrow(new TmdbUnavailableException("TMDB is currently unavailable"));

        mockMvc.perform(get("/people/12345").cookie(accessCookie(viewerId)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                .andExpect(jsonPath("$.message").value("TMDB is currently unavailable"));
    }

    private void mockValidSession(UUID viewerId) {
        when(userRepository.findSessionsInvalidatedAtById(any()))
                .thenReturn(java.util.Optional.of(sessionsInvalidatedAtView(viewerId)));
    }

    private UserRepository.SessionsInvalidatedAtView sessionsInvalidatedAtView(UUID viewerId) {
        return new UserRepository.SessionsInvalidatedAtView() {
            @Override
            public UUID getId() {
                return viewerId;
            }

            @Override
            public LocalDateTime getSessionsInvalidatedAt() {
                return null;
            }
        };
    }

    private Cookie accessCookie(UUID viewerId) {
        return new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, jwtService.generateToken(viewerId, TokenType.ACCESS));
    }
}
