package com.watchwise.watchwise_api.common.config;

import tools.jackson.databind.ObjectMapper;
import com.watchwise.watchwise_api.common.security.CsrfCookieFilter;
import com.watchwise.watchwise_api.common.security.JsonAccessDeniedHandler;
import com.watchwise.watchwise_api.common.security.JsonAuthenticationEntryPoint;
import com.watchwise.watchwise_api.common.security.JwtCookieAuthenticationFilter;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.PickCreationThrottleFilter;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.SpaCsrfTokenRequestHandler;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.time.Clock;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        return new JwtCookieAuthenticationFilter(jwtService, userRepository);
    }

    @Bean
    public PickCreationThrottleFilter pickCreationThrottleFilter(
            RequestThrottler requestThrottler,
            HandlerExceptionResolver handlerExceptionResolver,
            @Value("${app.rate-limit.pick-create.max-requests}") int maxRequests,
            @Value("${app.rate-limit.pick-create.window-minutes}") long windowMinutes
    ) {
        return new PickCreationThrottleFilter(requestThrottler, handlerExceptionResolver, maxRequests, windowMinutes);
    }

    @Bean
    public FilterRegistrationBean<PickCreationThrottleFilter> pickCreationThrottleFilterRegistration(
            PickCreationThrottleFilter filter
    ) {
        FilterRegistrationBean<PickCreationThrottleFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new JsonAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    public JsonAccessDeniedHandler jsonAccessDeniedHandler(ObjectMapper objectMapper) {
        return new JsonAccessDeniedHandler(objectMapper);
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    public CsrfAuthenticationStrategy csrfAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        return new CsrfAuthenticationStrategy(csrfTokenRepository);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter,
            PickCreationThrottleFilter pickCreationThrottleFilter,
            JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint,
            JsonAccessDeniedHandler jsonAccessDeniedHandler,
            CsrfTokenRepository csrfTokenRepository,
            CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                        .ignoringRequestMatchers("/auth/register", "/auth/login", "/auth/oauth/**", "/auth/refresh", "/auth/logout"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/logout-all").authenticated()
                        .requestMatchers("/auth/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(jsonAuthenticationEntryPoint)
                        .accessDeniedHandler(jsonAccessDeniedHandler))
                .addFilterBefore(jwtCookieAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(pickCreationThrottleFilter, JwtCookieAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .build();
    }
}
