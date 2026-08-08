package com.watchwise.watchwise_api.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CookieUtil {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    public static final String CSRF_TOKEN_COOKIE = "XSRF-TOKEN";
    public static final String REFRESH_TOKEN_PATH = "/auth/refresh";

    private final long accessTokenExpirationMinutes;
    private final long refreshTokenExpirationDays;
    private final boolean secureCookies;

    public CookieUtil(
            @Value("${app.jwt.expiration-minutes}") long accessTokenExpirationMinutes,
            @Value("${app.jwt.refresh-expiration-days}") long refreshTokenExpirationDays,
            @Value("${app.cookies.secure:true}") boolean secureCookies
    ) {
        this.accessTokenExpirationMinutes = accessTokenExpirationMinutes;
        this.refreshTokenExpirationDays = refreshTokenExpirationDays;
        this.secureCookies = secureCookies;
    }

    public ResponseCookie buildAccessTokenCookie(String token) {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(secureCookies)
                .path("/")
                .sameSite("Lax")
                .maxAge(Duration.ofMinutes(accessTokenExpirationMinutes))
                .build();
    }

    public ResponseCookie buildRefreshTokenCookie(String token) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(secureCookies)
                .path(REFRESH_TOKEN_PATH)
                .sameSite("Lax")
                .maxAge(Duration.ofDays(refreshTokenExpirationDays))
                .build();
    }

    public ResponseCookie clearCookie(String name, String path) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(secureCookies)
                .path(path)
                .sameSite("Lax")
                .maxAge(0)
                .build();
    }
}
