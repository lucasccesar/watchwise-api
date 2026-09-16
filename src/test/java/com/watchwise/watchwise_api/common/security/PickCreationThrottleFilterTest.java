package com.watchwise.watchwise_api.common.security;

import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PickCreationThrottleFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldConsumeMalformedPickCreationAttemptsAndResolveTheThirdAsTooManyRequests() throws Exception {
        UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        FilterChain filterChain = mock(FilterChain.class);
        HandlerExceptionResolver exceptionResolver = mock(HandlerExceptionResolver.class);
        PickCreationThrottleFilter filter = new PickCreationThrottleFilter(
                new RequestThrottler(Clock.systemUTC()), exceptionResolver, 2, 1);
        when(exceptionResolver.resolveException(any(), any(), isNull(), any(TooManyRequestsException.class)))
                .thenAnswer(invocation -> {
                    ((MockHttpServletResponse) invocation.getArgument(1)).setStatus(429);
                    return new ModelAndView();
                });

        filter.doFilter(malformedPickCreationRequest(), new MockHttpServletResponse(), filterChain);
        filter.doFilter(malformedPickCreationRequest(), new MockHttpServletResponse(), filterChain);
        MockHttpServletRequest thirdRequest = malformedPickCreationRequest();
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(thirdRequest, thirdResponse, filterChain);

        verify(filterChain, times(2)).doFilter(any(), any());
        verify(exceptionResolver).resolveException(eq(thirdRequest), eq(thirdResponse), isNull(), any(TooManyRequestsException.class));
        assertThat(thirdResponse.getStatus()).isEqualTo(429);
    }

    @Test
    void shouldUseAuthenticatedUuidOnlyForThePickCreationPostRoute() throws Exception {
        UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        RequestThrottler requestThrottler = mock(RequestThrottler.class);
        FilterChain filterChain = mock(FilterChain.class);
        PickCreationThrottleFilter filter = new PickCreationThrottleFilter(
                requestThrottler, mock(HandlerExceptionResolver.class), 2, 1);

        filter.doFilter(new MockHttpServletRequest("POST", "/picks-templates/template-id/picks"),
                new MockHttpServletResponse(), filterChain);
        filter.doFilter(new MockHttpServletRequest("GET", "/picks-templates/template-id/picks"),
                new MockHttpServletResponse(), filterChain);
        filter.doFilter(new MockHttpServletRequest("POST", "/picks/template-id"),
                new MockHttpServletResponse(), filterChain);

        verify(requestThrottler).checkAllowed(
                org.mockito.ArgumentMatchers.eq("pick-create|" + userId),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq(java.time.Duration.ofMinutes(1)));
        verify(filterChain, times(3)).doFilter(any(), any());
    }

    private MockHttpServletRequest malformedPickCreationRequest() {
        return new MockHttpServletRequest("POST", "/picks-templates/not-a-uuid/picks");
    }
}
