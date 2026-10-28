package com.watchwise.watchwise_api.common.security;

import tools.jackson.databind.ObjectMapper;
import com.watchwise.watchwise_api.common.exception.ApiError;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

@RequiredArgsConstructor
public class JwtCookieAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String token = extractTokenFromCookies(request);

        try {
            if (token != null && jwtService.isTokenValid(token, TokenType.ACCESS)
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                UUID userId = jwtService.extractUserId(token);

                if (isSessionStillValid(userId, token)) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userId, null, List.of());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (DataAccessException exception) {
            SecurityContextHolder.clearContext();
            writeDatabaseLookupError(request, response, exception);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeDatabaseLookupError(
            HttpServletRequest request, HttpServletResponse response, DataAccessException exception) throws IOException {
        boolean transientFailure = exception instanceof TransientDataAccessException
                || exception instanceof DataAccessResourceFailureException;
        HttpStatus status = transientFailure ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
        ApiError apiError = new ApiError(
                LocalDateTime.now(),
                status.value(),
                transientFailure ? "Service Unavailable" : "Internal Server Error",
                transientFailure ? "Authentication service is temporarily unavailable" : "An unexpected error occurred",
                request.getRequestURI());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(apiError));
    }

    private boolean isSessionStillValid(UUID userId, String token) {
        Optional<UserRepository.SessionsInvalidatedAtView> view = userRepository.findSessionsInvalidatedAtById(userId);
        if (view.isEmpty()) {
            return false;
        }

        if (jwtService.extractAuthVersion(token) != view.get().getAuthVersion()) {
            return false;
        }

        LocalDateTime sessionsInvalidatedAt = view.get().getSessionsInvalidatedAt();
        if (sessionsInvalidatedAt == null) {
            return true;
        }

        LocalDateTime issuedAt = LocalDateTime.ofInstant(jwtService.extractIssuedAt(token).toInstant(), ZoneId.systemDefault());
        return issuedAt.isAfter(sessionsInvalidatedAt);
    }

    private String extractTokenFromCookies(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        return Arrays.stream(cookies)
                .filter(cookie -> CookieUtil.ACCESS_TOKEN_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
