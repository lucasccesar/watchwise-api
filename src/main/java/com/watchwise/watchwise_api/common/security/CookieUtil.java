package com.watchwise.watchwise_api.common.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Component
public class CookieUtil {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    public static final String CSRF_TOKEN_COOKIE = "XSRF-TOKEN";

    private final long accessTokenExpirationMinutes;
    private final long refreshTokenExpirationDays;
    private final boolean secureCookies;
    private final String refreshTokenPath;

    public CookieUtil(
            @Value("${app.jwt.expiration-minutes}") long accessTokenExpirationMinutes,
            @Value("${app.jwt.refresh-expiration-days}") long refreshTokenExpirationDays,
            @Value("${app.cookies.secure:true}") boolean secureCookies,
            @Value("${server.servlet.context-path:}") String contextPath
    ) {
        this.accessTokenExpirationMinutes = accessTokenExpirationMinutes;
        this.refreshTokenExpirationDays = refreshTokenExpirationDays;
        this.secureCookies = secureCookies;
        this.refreshTokenPath = contextPath + "/auth/refresh";
    }

    public String getRefreshTokenPath() {
        return refreshTokenPath;
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
                .path(refreshTokenPath)
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

    public void addCookie(HttpServletResponse response, ResponseCookie cookie) {
        Cookie servletCookie = new Cookie(cookie.getName(), cookie.getValue());
        servletCookie.setPath(cookie.getPath());
        servletCookie.setMaxAge((int) cookie.getMaxAge().getSeconds());
        servletCookie.setHttpOnly(cookie.isHttpOnly());
        servletCookie.setSecure(cookie.isSecure());
        if (StringUtils.hasText(cookie.getSameSite())) {
            servletCookie.setAttribute("SameSite", cookie.getSameSite());
        }
        response.addCookie(servletCookie);
    }
}
