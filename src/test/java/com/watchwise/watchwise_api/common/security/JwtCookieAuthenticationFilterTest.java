package com.watchwise.watchwise_api.common.security;

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
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

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
        filter = new JwtCookieAuthenticationFilter(jwtService, userRepository);
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
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

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
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(jwtService.extractIssuedAt(TOKEN)).thenReturn(toDate(invalidatedAt.plusMinutes(1)));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("[doFilterInternal] Should Not Authenticate - When Token Was Issued Before Sessions Were Invalidated")
    void shouldNotAuthenticateWhenTokenWasIssuedBeforeSessionsWereInvalidated() throws Exception {
        LocalDateTime invalidatedAt = LocalDateTime.now();
        user.setSessionsInvalidatedAt(invalidatedAt);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
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
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(jwtService.extractIssuedAt(TOKEN)).thenReturn(toDate(invalidatedAt));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("[doFilterInternal] Should Not Authenticate - When The User No Longer Exists")
    void shouldNotAuthenticateWhenTheUserNoLongerExists() throws Exception {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("[doFilterInternal] Should Not Look Up The User - When The Token Itself Is Invalid")
    void shouldNotLookUpTheUserWhenTheTokenItselfIsInvalid() throws Exception {
        when(jwtService.isTokenValid(TOKEN, TokenType.ACCESS)).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userRepository, org.mockito.Mockito.never()).findById(any());
    }

    private Date toDate(LocalDateTime localDateTime) {
        return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
}
