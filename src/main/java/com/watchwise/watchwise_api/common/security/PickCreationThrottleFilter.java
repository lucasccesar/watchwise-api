package com.watchwise.watchwise_api.common.security;

import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

public class PickCreationThrottleFilter extends OncePerRequestFilter {

    private static final String PICK_CREATION_PATH = "/picks-templates/*/picks";

    private final RequestThrottler requestThrottler;
    private final HandlerExceptionResolver handlerExceptionResolver;
    private final int maxRequests;
    private final Duration window;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public PickCreationThrottleFilter(
            RequestThrottler requestThrottler,
            HandlerExceptionResolver handlerExceptionResolver,
            int maxRequests,
            long windowMinutes
    ) {
        this.requestThrottler = requestThrottler;
        this.handlerExceptionResolver = handlerExceptionResolver;
        this.maxRequests = maxRequests;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        UUID userId = authenticatedUserId();
        if (!isPickCreationRequest(request) || userId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            requestThrottler.checkAllowed("pick-create|" + userId, maxRequests, window);
            filterChain.doFilter(request, response);
        } catch (TooManyRequestsException ex) {
            handlerExceptionResolver.resolveException(request, response, null, ex);
        }
    }

    private boolean isPickCreationRequest(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestPath = request.getRequestURI();
        if (contextPath != null && !contextPath.isEmpty() && requestPath.startsWith(contextPath)) {
            requestPath = requestPath.substring(contextPath.length());
        }
        return "POST".equals(request.getMethod()) && pathMatcher.match(PICK_CREATION_PATH, requestPath);
    }

    private UUID authenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof UUID userId ? userId : null;
    }
}
