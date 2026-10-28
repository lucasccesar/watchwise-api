package com.watchwise.watchwise_api.common.security;

import tools.jackson.databind.ObjectMapper;
import com.watchwise.watchwise_api.common.exception.ApiError;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtCookieAuthenticationFilterTest {

    private static final String TOKEN = "access-token-value";

    @Mock
    private JwtService jwtService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private JwtCookieAuthenticationFilter filter;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        filter = new JwtCookieAuthenticationFilter(jwtService, userRepository, objectMapper);
        userId = UUID.randomUUID();
        user = User.builder().id(userId).username("lucas").email("lucas@email.com").password("hash").build();

        SecurityContextHolder.clearContext();
        lenient().when(request.getCookies()).thenReturn(new Cookie[]{new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, TOKEN)});
        lenient().when(jwtService.isTokenValid(TOKEN, TokenType.ACCESS)).thenReturn(true);
        lenient().when(jwtService.extractUserId(TOKEN)).thenReturn(userId);
    }

    @Test
    @DisplayName("[doFilterInternal] Should Authenticate - When Token Is Valid And User Never Had Sessions Invalidated")
    void shouldAuthenticateWhenTokenIsValidAndUserNeverHadSessionsInvalidated() throws Exception {
        user.setSessionsInvalidatedAt(null);
        when(userRepository.findSessionsInvalidatedAtById(userId)).thenReturn(Optional.of(sessionsInvalidatedAtView(userId, user.getSessionsInvalidatedAt())));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(userId);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("[doFilterInternal] Should Authenticate - When Token Was Issued After Sessions Were Invalidated")
    void shouldAuthenticateWhenTokenWasIssuedAfterSessionsWereInvalidated() throws Exception {
        LocalDateTime invalidatedAt = LocalDateTime.now().minusMinutes(5);
        user.setSessionsInvalidatedAt(invalidatedAt);
        when(userRepository.findSessionsInvalidatedAtById(userId)).thenReturn(Optional.of(sessionsInvalidatedAtView(userId, user.getSessionsInvalidatedAt())));
        when(jwtService.extractIssuedAt(TOKEN)).thenReturn(toDate(invalidatedAt.plusMinutes(1)));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("[doFilterInternal] Should Not Authenticate - When Token Was Issued Before Sessions Were Invalidated")
    void shouldNotAuthenticateWhenTokenWasIssuedBeforeSessionsWereInvalidated() throws Exception {
        LocalDateTime invalidatedAt = LocalDateTime.now();
        user.setSessionsInvalidatedAt(invalidatedAt);
        when(userRepository.findSessionsInvalidatedAtById(userId)).thenReturn(Optional.of(sessionsInvalidatedAtView(userId, user.getSessionsInvalidatedAt())));
        when(jwtService.extractIssuedAt(TOKEN)).thenReturn(toDate(invalidatedAt.minusMinutes(1)));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("[doFilterInternal] Should Not Authenticate - When Token Was Issued Exactly When Sessions Were Invalidated")
    void shouldNotAuthenticateWhenTokenWasIssuedExactlyWhenSessionsWereInvalidated() throws Exception {
        LocalDateTime invalidatedAt = LocalDateTime.now();
        user.setSessionsInvalidatedAt(invalidatedAt);
        when(userRepository.findSessionsInvalidatedAtById(userId)).thenReturn(Optional.of(sessionsInvalidatedAtView(userId, user.getSessionsInvalidatedAt())));
        when(jwtService.extractIssuedAt(TOKEN)).thenReturn(toDate(invalidatedAt));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("[doFilterInternal] Should Not Authenticate - When The User No Longer Exists")
    void shouldNotAuthenticateWhenTheUserNoLongerExists() throws Exception {
        when(userRepository.findSessionsInvalidatedAtById(userId)).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("[doFilterInternal] Should Not Authenticate - When Token Authentication Version Is Stale")
    void shouldNotAuthenticateWhenTokenAuthenticationVersionIsStale() throws Exception {
        when(userRepository.findSessionsInvalidatedAtById(userId))
                .thenReturn(Optional.of(sessionsInvalidatedAtView(userId, null, 4)));
        when(jwtService.extractAuthVersion(TOKEN)).thenReturn(3L);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("[doFilterInternal] Should Handle A Transient Session Lookup Failure - With A 503 ApiError")
    void shouldHandleTransientSessionLookupFailureWithoutContinuingTheFilterChain() throws Exception {
        when(userRepository.findSessionsInvalidatedAtById(userId))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        when(objectMapper.writeValueAsString(any(ApiError.class))).thenReturn("{\"status\":503}");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        org.assertj.core.api.Assertions.assertThatCode(() -> filter.doFilterInternal(request, response, filterChain))
                .doesNotThrowAnyException();

        verify(response).setStatus(503);
        verify(filterChain, org.mockito.Mockito.never()).doFilter(request, response);
    }

    @Test
    @DisplayName("[doFilterInternal] Should Keep Unexpected Repository Failures As Generic 500")
    void shouldKeepUnexpectedRepositoryFailuresAsGeneric500() throws Exception {
        when(userRepository.findSessionsInvalidatedAtById(userId))
                .thenThrow(new InvalidDataAccessApiUsageException("query details"));
        when(objectMapper.writeValueAsString(any(ApiError.class))).thenReturn("{\"status\":500}");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(500);
        verify(filterChain, org.mockito.Mockito.never()).doFilter(request, response);
    }


    @Test
    @DisplayName("[doFilterInternal] Should Not Look Up The User - When The Token Itself Is Invalid")
    void shouldNotLookUpTheUserWhenTheTokenItselfIsInvalid() throws Exception {
        when(jwtService.isTokenValid(TOKEN, TokenType.ACCESS)).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userRepository, org.mockito.Mockito.never()).findSessionsInvalidatedAtById(any());
    }

    private Date toDate(LocalDateTime localDateTime) {
        return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
    }

    private UserRepository.SessionsInvalidatedAtView sessionsInvalidatedAtView(UUID id, LocalDateTime sessionsInvalidatedAt) {
        return sessionsInvalidatedAtView(id, sessionsInvalidatedAt, 0);
    }

    private UserRepository.SessionsInvalidatedAtView sessionsInvalidatedAtView(
            UUID id, LocalDateTime sessionsInvalidatedAt, long authVersion) {
        return new UserRepository.SessionsInvalidatedAtView() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public LocalDateTime getSessionsInvalidatedAt() {
                return sessionsInvalidatedAt;
            }

            @Override
            public long getAuthVersion() {
                return authVersion;
            }

        };
    }
}
