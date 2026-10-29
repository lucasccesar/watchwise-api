package com.watchwise.watchwise_api.common.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
    public static final String REGISTRATION_PROOF_COOKIE = "registration_proof";

    private final long accessTokenExpirationMinutes;
    private final long refreshTokenExpirationDays;
    private final long rememberMeRefreshTokenExpirationDays;
    private final boolean secureCookies;
    private final String refreshTokenPath;
    private final String csrfTokenPath;
    private final String registrationProofPath;

    @Value("${app.auth.email-confirmation.token-ttl-minutes:30}")
    private long emailConfirmationTokenTtlMinutes = 30;

    @Autowired
    public CookieUtil(
            @Value("${app.jwt.expiration-minutes}") long accessTokenExpirationMinutes,
            @Value("${app.jwt.refresh-expiration-days}") long refreshTokenExpirationDays,
            @Value("${app.jwt.remember-me-refresh-expiration-days:30}") long rememberMeRefreshTokenExpirationDays,
            @Value("${app.cookies.secure:true}") boolean secureCookies,
            @Value("${server.servlet.context-path:}") String contextPath
    ) {
        this.accessTokenExpirationMinutes = accessTokenExpirationMinutes;
        this.refreshTokenExpirationDays = refreshTokenExpirationDays;
        this.rememberMeRefreshTokenExpirationDays = rememberMeRefreshTokenExpirationDays;
        this.secureCookies = secureCookies;
        this.refreshTokenPath = contextPath + "/auth/refresh";
        this.csrfTokenPath = contextPath.isEmpty() ? "/" : contextPath;
        this.registrationProofPath = contextPath + "/auth";
    }

    public CookieUtil(
            long accessTokenExpirationMinutes,
            long refreshTokenExpirationDays,
            boolean secureCookies,
            String contextPath) {
        this(accessTokenExpirationMinutes, refreshTokenExpirationDays, 30, secureCookies, contextPath);
    }

    public String getRefreshTokenPath() {
        return refreshTokenPath;
    }

    public String getCsrfTokenPath() {
        return csrfTokenPath;
    }

    public String getRegistrationProofPath() {
        return registrationProofPath;
    }

    public ResponseCookie buildAccessTokenCookie(String token) {
        return buildAccessTokenCookie(token, true);
    }

    public ResponseCookie buildAccessTokenCookie(String token, boolean rememberMe) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(ACCESS_TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(secureCookies)
                .path("/")
                .sameSite("Lax");
        if (rememberMe) {
            cookie.maxAge(Duration.ofMinutes(accessTokenExpirationMinutes));
        }
        return cookie.build();
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

    public ResponseCookie buildRefreshTokenCookie(String token, boolean rememberMe) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(secureCookies)
                .path(refreshTokenPath)
                .sameSite("Lax");
        if (rememberMe) {
            cookie.maxAge(Duration.ofDays(rememberMeRefreshTokenExpirationDays));
        }
        return cookie.build();
    }

    public ResponseCookie buildRegistrationProofCookie(String proof) {
        return ResponseCookie.from(REGISTRATION_PROOF_COOKIE, proof)
                .httpOnly(true)
                .secure(secureCookies)
                .path(registrationProofPath)
                .sameSite("Strict")
                .maxAge(Duration.ofMinutes(emailConfirmationTokenTtlMinutes))
                .build();
    }

    public ResponseCookie clearRegistrationProofCookie() {
        return ResponseCookie.from(REGISTRATION_PROOF_COOKIE, "")
                .httpOnly(true)
                .secure(secureCookies)
                .path(registrationProofPath)
                .sameSite("Strict")
                .maxAge(Duration.ZERO)
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
