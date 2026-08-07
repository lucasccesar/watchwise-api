package com.watchwise.watchwise_api.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CookieUtil {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";

    private final long expirationMinutes;
    private final boolean secureCookies;

    public CookieUtil(
            @Value("${app.jwt.expiration-minutes}") long expirationMinutes,
            @Value("${app.cookies.secure:true}") boolean secureCookies
    ) {
        this.expirationMinutes = expirationMinutes;
        this.secureCookies = secureCookies;
    }

    public ResponseCookie buildAccessTokenCookie(String token) {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(secureCookies)
                .path("/")
                .sameSite("Lax")
                .maxAge(Duration.ofMinutes(expirationMinutes))
                .build();
    }
}