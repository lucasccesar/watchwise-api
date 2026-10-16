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
import com.watchwise.watchwise_api.person.dto.PersonCreditSort;
import com.watchwise.watchwise_api.person.dto.PersonCreditSortDirection;
import com.watchwise.watchwise_api.person.dto.PersonCreditDTO;
import com.watchwise.watchwise_api.person.dto.PersonViewDTO;
import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.person.service.PersonViewService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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
@Testcontainers
class PersonControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private PersonService personService;

    @MockitoBean
    private PersonViewService personViewService;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        reset(personService, personViewService, userRepository);
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
    void shouldReturnVisualPersonViewWhenAuthenticatedWithAllFilters() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);
        PersonViewDTO expected = new PersonViewDTO(null, 2481L, null, null,
                new PageResponseDTO<>(java.util.List.of(), 2, 24, 0, 0, false));
        when(personViewService.getPersonView(viewerId, "287", PersonParticipation.CAST,
                WatchStatus.PARTIALLY_WATCHED, true, PersonCreditSort.AVERAGE_SCORE,
                PersonCreditSortDirection.DESC, 2, 24)).thenReturn(expected);

        mockMvc.perform(get("/people/287/view")
                        .param("participation", "CAST")
                        .param("watchStatus", "PARTIALLY_WATCHED")
                        .param("inWatchlist", "true")
                        .param("sortBy", "AVERAGE_SCORE")
                        .param("sortDirection", "DESC")
                        .param("page", "2")
                        .param("size", "24")
                        .cookie(accessCookie(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followersCount").value(2481))
                .andExpect(jsonPath("$.credits.page").value(2))
                .andExpect(jsonPath("$.credits.size").value(24));

        verify(personViewService).getPersonView(viewerId, "287", PersonParticipation.CAST,
                WatchStatus.PARTIALLY_WATCHED, true, PersonCreditSort.AVERAGE_SCORE,
                PersonCreditSortDirection.DESC, 2, 24);
    }

    @Test
    void shouldKeepRawPersonResponseShapeUnchanged() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);
        PersonResponseDTO expected = new PersonResponseDTO(
                new PersonDetailsDTO("287", "Ada Example", null, null, null, null,
                        null, null, "Acting", java.util.List.of(), false),
                new PersonProgressDTO(1, 0, 0.0),
                new PageResponseDTO<>(java.util.List.of(new PersonCreditDTO("603", MovieOrSeriesType.MOVIE,
                        "The Matrix", null, "1999-03-31", java.util.List.of(), java.util.List.of(),
                        PersonParticipation.CAST, false, false)), 1, 24, 1, 1, false));
        when(personService.getPerson(viewerId, "287", PersonParticipation.ALL, null, null)).thenReturn(expected);

        mockMvc.perform(get("/people/287").cookie(accessCookie(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.person.tmdbId").value("287"))
                .andExpect(jsonPath("$.credits.content[0].title").value("The Matrix"))
                .andExpect(jsonPath("$.hero").doesNotExist())
                .andExpect(jsonPath("$.followersCount").doesNotExist())
                .andExpect(jsonPath("$.credits.content[0].stats").doesNotExist());
    }

    @Test
    void shouldRequireAuthenticationForVisualPersonView() throws Exception {
        mockMvc.perform(get("/people/287/view"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/people/287/view"));
        verifyNoInteractions(personViewService);
    }

    @Test
    void shouldReturnBadRequestWhenVisualPersonIdIsNotNumeric() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);
        when(personViewService.getPersonView(viewerId, "abc", null,
                null, null, null, null, null, null))
                .thenThrow(new BadRequestException("personTmdbId must be a numeric TMDB id up to 20 digits"));

        mockMvc.perform(get("/people/abc/view").cookie(accessCookie(viewerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("personTmdbId must be a numeric TMDB id up to 20 digits"));
    }

    @Test
    void shouldReturnBadRequestWhenVisualFilterEnumIsInvalid() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);

        mockMvc.perform(get("/people/287/view").param("sortBy", "INVALID").cookie(accessCookie(viewerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("parameter 'sortBy'")));
        verifyNoInteractions(personViewService);
    }

    @Test
    void shouldReturnBadRequestWhenVisualPageOrSizeIsInvalid() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);
        when(personViewService.getPersonView(viewerId, "287", null,
                null, null, null, null, -1, null))
                .thenThrow(new BadRequestException("Page number must be greater than or equal to 0"));

        mockMvc.perform(get("/people/287/view").param("page", "-1").cookie(accessCookie(viewerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Page number must be greater than or equal to 0"));
        verify(personViewService).getPersonView(viewerId, "287", null,
                null, null, null, null, -1, null);
    }

    @Test
    void shouldReturnBadRequestWhenVisualSizeIsZero() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);
        when(personViewService.getPersonView(viewerId, "287", null,
                null, null, null, null, null, 0))
                .thenThrow(new BadRequestException("Page size must be greater than 0"));

        mockMvc.perform(get("/people/287/view").param("size", "0").cookie(accessCookie(viewerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Page size must be greater than 0"));
        verify(personViewService).getPersonView(viewerId, "287", null,
                null, null, null, null, null, 0);
    }

    @Test
    void shouldClampVisualPageSizeAboveTwentyFour() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);
        when(personViewService.getPersonView(viewerId, "287", null, null, null,
                null, null, null, 100)).thenReturn(new PersonViewDTO(null, 0L, null, null,
                new PageResponseDTO<>(java.util.List.of(), 0, 24, 0, 0, false)));

        mockMvc.perform(get("/people/287/view").param("size", "100").cookie(accessCookie(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credits.size").value(24));

        verify(personViewService).getPersonView(viewerId, "287", null,
                null, null, null, null, null, 100);
    }

    @Test
    void shouldReturnNotFoundWhenVisualTmdbPersonDoesNotExist() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);
        when(personViewService.getPersonView(viewerId, "999", null,
                null, null, null, null, null, null))
                .thenThrow(new NotFoundException("No person found on TMDB for the given id"));

        mockMvc.perform(get("/people/999/view").cookie(accessCookie(viewerId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldReturnBadGatewayWhenVisualAggregateIsUnavailable() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);
        when(personViewService.getPersonView(viewerId, "287", null,
                null, null, null, null, null, null))
                .thenThrow(new TmdbUnavailableException("TMDB is currently unavailable"));

        mockMvc.perform(get("/people/287/view").cookie(accessCookie(viewerId)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502));
    }

    @Test
    void shouldAllowVisualPersonViewWithoutHero() throws Exception {
        UUID viewerId = UUID.randomUUID();
        mockValidSession(viewerId);
        when(personViewService.getPersonView(viewerId, "287", null,
                null, null, null, null, null, null))
                .thenReturn(new PersonViewDTO(null, 1L, null, null,
                        new PageResponseDTO<>(java.util.List.of(), 0, 24, 0, 0, false)));

        mockMvc.perform(get("/people/287/view").cookie(accessCookie(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hero").doesNotExist())
                .andExpect(jsonPath("$.followersCount").value(1));
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
        when(personService.getPerson(viewerId, "abc", PersonParticipation.ALL, null, null))
                .thenThrow(new BadRequestException("personTmdbId must be a numeric TMDB id up to 20 digits"));

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
